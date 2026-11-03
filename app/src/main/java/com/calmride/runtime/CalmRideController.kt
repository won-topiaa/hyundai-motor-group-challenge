package com.calmride.runtime

import android.content.Context
import android.content.Intent
import com.calmride.audio.AudioOutput
import com.calmride.core.CoachingEngine
import com.calmride.core.CoachingTip
import com.calmride.core.Cue
import com.calmride.core.CueEngine
import com.calmride.core.DriverIdentifier
import com.calmride.core.DriverProfile
import com.calmride.core.Gear
import com.calmride.core.ProfileTuner
import com.calmride.core.RouteEvent
import com.calmride.core.StopDetector
import com.calmride.core.TripRecord
import com.calmride.core.TripRecorder
import com.calmride.core.VehicleSnapshot
import com.calmride.core.VoiceCommand
import com.calmride.core.VoiceCommandParser
import com.calmride.data.ProfileStore
import com.calmride.vehicle.CarApiSource
import com.calmride.vehicle.LocationSource
import com.calmride.vehicle.NaviSource
import com.calmride.vehicle.PleosVehicleSource
import com.calmride.vehicle.SimulatedDrive
import com.calmride.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val modeOn: Boolean = false,
    val primingSecondsLeft: Int? = null,
    val stopped: Boolean? = null,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val nextEvent: RouteEvent? = null,
    val lastCue: Cue? = null,
    val profiles: List<DriverProfile> = emptyList(),
    val activeProfileId: String = "",
    val suggestMode: Boolean = false,
    val drivingRestricted: Boolean = false,
    val tripActive: Boolean = false,
    val pendingTrip: TripRecord? = null,
    val coaching: CoachingTip? = null,
    val demoRunning: Boolean = false,
    val sources: Map<String, String> = emptyMap(),
) {
    val activeProfile: DriverProfile? get() = profiles.firstOrNull { it.id == activeProfileId }
}

/**
 * Wires vehicle, navigation, voice and audio together. Lives as long as the app process;
 * [CalmRideService] keeps the process in the foreground while motion-sickness mode is on.
 */
class CalmRideController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ProfileStore(context)
    private val audio = AudioOutput(context)
    private val cueEngine = CueEngine()
    private val stopDetector = StopDetector()
    private var recorder: TripRecorder? = null
    private var routeEvents: List<RouteEvent> = emptyList()
    private var restoreAir: PleosVehicleSource.RestoreState? = null
    private var started = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val vehicleSource = PleosVehicleSource(context, ::onVehicleSignal) { status("차량", it) }
    private val carApi = CarApiSource(
        context,
        onSpeed = { speed -> updateVehicle { copy(speedMps = speed) } },
        onGear = { gear -> updateVehicle { copy(gear = gear) } },
        onRegen = { s, level -> updateVehicle { copy(regenState = s, regenLevel = level) } },
        onRestriction = { restricted -> _state.update { it.copy(drivingRestricted = restricted) } },
        onStatus = { status("AAOS", it) },
    )
    private val navi = NaviSource(context, ::onRouteEvents) { status("내비", it) }
    private val location = LocationSource(context, { lat, lon -> updateVehicle(log = false) { copy(latitude = lat, longitude = lon) } }) { status("위치", it) }
    private val voice = VoiceController(context, ::onVoiceText) { status("음성", it) }
    private val demo = SimulatedDrive(
        scope,
        onSpeed = { s -> updateVehicle { copy(speedMps = s) } },
        onGear = { g -> updateVehicle { copy(gear = g) } },
        onBrake = { b -> updateVehicle { copy(brakePressed = b) } },
        onSignal = { s -> updateVehicle { copy(turnSignal = s) } },
        onSteering = { d -> updateVehicle { copy(steeringDeg = d) } },
        onRoute = ::onRouteEvents,
        onFinished = { _state.update { it.copy(demoRunning = false) } },
    )

    fun start() {
        if (started) return
        started = true
        val profiles = store.load()
        val active = store.activeProfileId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
        _state.update { it.copy(profiles = profiles, activeProfileId = active) }
        applyProfileConfig()
        vehicleSource.start()
        carApi.start()
        navi.start()
        location.start()
        voice.start()
        // Recognize the driver from the seat once the vehicle service is up.
        scope.launch {
            kotlinx.coroutines.delay(2500)
            vehicleSource.readDriverSeat { foreAft, height ->
                val match = DriverIdentifier.match(_state.value.profiles, foreAft, height)
                if (match != null && match.id != _state.value.activeProfileId) {
                    selectProfile(match.id)
                    voice.speak("${match.name}님, 반가워요.")
                }
            }
        }
    }

    fun onPermissionsGranted() {
        carApi.subscribeSpeed()
        carApi.readRegen()
    }

    // ---- motion-sickness mode ----

    fun setMode(on: Boolean) {
        if (_state.value.modeOn == on) return
        _state.update { it.copy(modeOn = on, suggestMode = false) }
        if (on) {
            CalmRideService.start(context)
            cueEngine.reset()
            vehicleSource.applyFreshAir { restoreAir = it }
            voice.speak("멀미 모드를 시작할게요. 차가 돌거나 멈추기 전에 소리로 알려 드려요.")
            if (_state.value.stopped == true) voice.speak("출발 전에 1분 동안 낮은 소리를 들려 드릴게요.")
        } else {
            audio.stopPriming()
            restoreAir?.let(vehicleSource::restoreAir)
            restoreAir = null
            endTrip()
            context.stopService(Intent(context, CalmRideService::class.java))
            voice.speak("멀미 모드를 끌게요.")
        }
    }

    fun startPriming() {
        val s = _state.value
        if (s.primingSecondsLeft != null) return
        // Safety: the 1-minute tone only plays while the car is stopped (or in the demo without a car).
        if (s.stopped != true && !(s.stopped == null && !vehicleSource.connected)) return
        val volume = s.activeProfile?.cueVolume ?: 0.6f
        audio.startPriming(
            volume,
            onTick = { left -> scope.launch { _state.update { it.copy(primingSecondsLeft = left) } } },
            onDone = { completed ->
                scope.launch {
                    _state.update { it.copy(primingSecondsLeft = null) }
                    if (completed) voice.speak("준비가 끝났어요. 편안한 이동 되세요.")
                }
            },
        )
        _state.update { it.copy(primingSecondsLeft = 60) }
    }

    fun stopPriming() = audio.stopPriming()

    fun dismissSuggestion() = _state.update { it.copy(suggestMode = false) }

    fun listenVoice() = voice.listen()

    // ---- demo ----

    fun toggleDemo() {
        if (demo.running) {
            demo.stop()
            _state.update { it.copy(demoRunning = false) }
        } else {
            _state.update { it.copy(demoRunning = true, vehicle = VehicleSnapshot(timestampMs = System.currentTimeMillis())) }
            demo.start()
        }
    }

    // ---- profiles ----

    fun selectProfile(id: String) {
        store.activeProfileId = id
        _state.update { it.copy(activeProfileId = id) }
        applyProfileConfig()
    }

    fun addProfile(name: String) {
        val profile = DriverProfile(id = "driver-${System.currentTimeMillis()}", name = name.ifBlank { "운전자 ${_state.value.profiles.size + 1}" })
        saveProfiles(_state.value.profiles + profile)
        selectProfile(profile.id)
        rememberSeatFor(profile.id)
    }

    /** Saves the current seat position so this driver is recognized automatically next time. */
    fun rememberSeatFor(id: String) {
        vehicleSource.readDriverSeat { foreAft, height ->
            if (foreAft == null && height == null) return@readDriverSeat
            saveProfiles(_state.value.profiles.map { if (it.id == id) it.copy(seatForeAft = foreAft, seatHeight = height) else it })
        }
    }

    fun setVolume(volume: Float) {
        val id = _state.value.activeProfileId
        saveProfiles(_state.value.profiles.map { if (it.id == id) it.copy(cueVolume = volume) else it })
    }

    /** Passenger comfort after the trip (1-5). Updates the profile and produces one coaching tip. */
    fun submitComfort(score: Int) {
        val pending = _state.value.pendingTrip ?: return
        val profile = _state.value.activeProfile ?: return
        val record = pending.copy(comfortScore = score)
        val tuned = ProfileTuner.afterTrip(profile, record)
        val tip = CoachingEngine.tipFor(tuned, record)
        saveProfiles(_state.value.profiles.map { if (it.id == tuned.id) tuned else it })
        _state.update { it.copy(pendingTrip = null, coaching = tip) }
        applyProfileConfig()
        voice.speak(CoachingEngine.spoken(tip))
    }

    fun dismissCoaching() = _state.update { it.copy(coaching = null) }

    private fun saveProfiles(profiles: List<DriverProfile>) {
        store.save(profiles)
        _state.update { it.copy(profiles = profiles) }
    }

    private fun applyProfileConfig() {
        _state.value.activeProfile?.let { cueEngine.updateConfig(ProfileTuner.cueConfig(it)) }
    }

    // ---- signal handling ----

    private fun onVehicleSignal(signal: PleosVehicleSource.VehicleSignal) {
        when (signal) {
            is PleosVehicleSource.VehicleSignal.Brake -> updateVehicle { copy(brakePressed = signal.pressed) }
            is PleosVehicleSource.VehicleSignal.Signal -> updateVehicle { copy(turnSignal = signal.state) }
            is PleosVehicleSource.VehicleSignal.Steering -> updateVehicle(log = false) { copy(steeringDeg = signal.degrees) }
            is PleosVehicleSource.VehicleSignal.RearSeat -> {
                val wasOccupied = _state.value.vehicle.rearSeatOccupied
                updateVehicle { copy(rearSeatOccupied = signal.occupied) }
                if (signal.occupied && !wasOccupied && !_state.value.modeOn) {
                    _state.update { it.copy(suggestMode = true) }
                    voice.speak("뒷좌석에 탑승하셨네요. 멀미 모드를 켤까요?")
                }
            }
        }
    }

    private fun updateVehicle(log: Boolean = true, change: VehicleSnapshot.() -> VehicleSnapshot) {
        scope.launch {
            val snapshot = _state.value.vehicle.change().copy(timestampMs = System.currentTimeMillis())
            val stopped = stopDetector.update(snapshot)
            _state.update { it.copy(vehicle = snapshot, stopped = stopped) }
            if (log) handleTrip(snapshot)
            if (!_state.value.modeOn) return@launch
            recorder?.onVehicle(snapshot)
            play(cueEngine.onVehicle(snapshot))
        }
    }

    private fun onRouteEvents(events: List<RouteEvent>) {
        scope.launch {
            routeEvents = events
            _state.update { it.copy(nextEvent = events.minByOrNull { e -> e.distanceM }) }
            if (!_state.value.modeOn) return@launch
            recorder?.onRouteEvents(events)
            play(cueEngine.onRouteEvents(events, _state.value.vehicle))
        }
    }

    private fun play(cues: List<Cue>) {
        val volume = _state.value.activeProfile?.cueVolume ?: 0.6f
        cues.forEach { cue ->
            audio.playCue(cue, volume)
            recorder?.onCue()
            _state.update { it.copy(lastCue = cue) }
        }
    }

    /** A trip starts when the car leaves Park in motion-sickness mode and ends back in Park. */
    private fun handleTrip(v: VehicleSnapshot) {
        val s = _state.value
        if (!s.modeOn) return
        if (!s.tripActive && (v.gear == Gear.DRIVE || (v.speedMps ?: 0f) > 2f)) {
            recorder = TripRecorder(v.timestampMs).also { it.onRouteEvents(routeEvents) }
            cueEngine.reset()
            audio.stopPriming()
            _state.update { it.copy(tripActive = true, coaching = null) }
        } else if (s.tripActive && v.gear == Gear.PARK) {
            endTrip()
        }
    }

    private fun endTrip() {
        val rec = recorder ?: return
        recorder = null
        val stats = rec.finish()
        _state.update { it.copy(tripActive = false) }
        if (stats.durationSec < 20) return
        _state.update { it.copy(pendingTrip = TripRecord(System.currentTimeMillis(), stats)) }
        voice.speak("도착했어요. 동승자 컨디션은 어땠나요? 화면에서 1점부터 5점까지 골라 주세요.")
    }

    private fun onVoiceText(text: String) {
        scope.launch {
            when (val cmd = VoiceCommandParser.parse(text)) {
                VoiceCommand.ModeOn -> setMode(true)
                VoiceCommand.ModeOff -> setMode(false)
                VoiceCommand.StartPriming -> startPriming()
                is VoiceCommand.SwitchDriver -> _state.value.profiles.firstOrNull { cmd.name in it.name }?.let {
                    selectProfile(it.id)
                    voice.speak("${it.name}님 프로필로 바꿨어요.")
                }
                null -> Unit
            }
        }
    }

    private fun status(source: String, message: String) {
        scope.launch { _state.update { it.copy(sources = it.sources + (source to message)) } }
    }

    fun shutdown() {
        demo.stop()
        audio.release()
        vehicleSource.stop()
        carApi.stop()
        navi.stop()
        location.stop()
        voice.stop()
        started = false
    }
}
