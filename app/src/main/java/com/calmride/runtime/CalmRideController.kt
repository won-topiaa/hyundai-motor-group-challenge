package com.calmride.runtime

import android.content.Context
import com.calmride.audio.AudioOutput
import com.calmride.core.CoachingEngine
import com.calmride.core.Cue
import com.calmride.core.CueEngine
import com.calmride.core.DayReport
import com.calmride.core.DayReports
import com.calmride.core.DriverIdentifier
import com.calmride.core.DriverProfile
import com.calmride.core.Gear
import com.calmride.core.PrimingBalance
import com.calmride.core.ProfileTuner
import com.calmride.core.RideEvent
import com.calmride.core.RideIntro
import com.calmride.core.RideTracker
import com.calmride.core.RouteEvent
import com.calmride.core.RouteEventKind
import com.calmride.core.SeatSide
import com.calmride.core.StopDetector
import com.calmride.core.ToneSynth
import com.calmride.core.TripRecord
import com.calmride.core.TripRecorder
import com.calmride.core.TurnSignal
import com.calmride.core.VehicleSnapshot
import com.calmride.core.VoiceCommand
import com.calmride.core.VoiceCommandParser
import com.calmride.data.ProfileStore
import com.calmride.vehicle.CarApiSource
import com.calmride.vehicle.DemoStyle
import com.calmride.vehicle.LocationSource
import com.calmride.vehicle.NaviSource
import com.calmride.vehicle.PleosVehicleSource
import com.calmride.vehicle.SimulatedDrive
import com.calmride.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The two parts of the once-per-ride introduction: the cue sounds, then the offer of the 1-minute tone. */
enum class IntroPart { CUES, PRIMING }

data class UiState(
    val modeOn: Boolean = false,
    val primingSecondsLeft: Int? = null,
    val stopped: Boolean? = null,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val nextEvent: RouteEvent? = null,
    val lastCue: Cue? = null,
    val profiles: List<DriverProfile> = emptyList(),
    val activeProfileId: String = "",
    /** A passenger just boarded and the app asked whether to turn the comfort mode on. */
    val suggestMode: Boolean = false,
    val drivingRestricted: Boolean = false,
    val rideActive: Boolean = false,
    /** The car has moved since this passenger boarded, so the before-departure tone is no longer on offer. */
    val rideMoved: Boolean = false,
    val demoRunning: Boolean = false,
    val sources: Map<String, String> = emptyMap(),
    /** Passenger side from the seat sensors; [seatOverride] wins when the driver picked one. */
    val detectedSeat: SeatSide = SeatSide.CENTER,
    val seatOverride: SeatSide? = null,
    val balanceBoostDb: Float = PrimingBalance.DEFAULT_BOOST_DB,
    /** The boarding question is open: 1 minute before departure, or cues only. */
    val askPriming: Boolean = false,
    /** The comfort question near the destination is open. */
    val askComfort: Boolean = false,
    val rideComfort: Int? = null,
    /** The introduction playing after the passenger asked for the mode; null when none plays. */
    val intro: IntroPart? = null,
    /** What the introduction is saying right now, shown for the driver. */
    val introCaption: String? = null,
    val dayReport: DayReport = DayReport(),
) {
    val activeProfile: DriverProfile? get() = profiles.firstOrNull { it.id == activeProfileId }
    val seat: SeatSide get() = seatOverride ?: detectedSeat
}

/**
 * Wires vehicle, navigation, voice and audio together. Lives as long as the app process;
 * [CalmRideService] keeps the process in the foreground for the shift.
 *
 * A ride runs from a passenger boarding to the seats being empty again. Every ride is recorded for the driver's
 * day report; cues and fresh air run only on the rides where the passenger asked for them.
 */
class CalmRideController(private val context: Context) {

    /** What an answer heard on the microphone refers to. */
    private enum class Question { RIDE_MODE, PRIMING, COMFORT }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ProfileStore(context)
    private val audio = AudioOutput(context)
    private val cueEngine = CueEngine()
    private val stopDetector = StopDetector()
    private val rideTracker = RideTracker()
    private var recorder: TripRecorder? = null
    private var routeEvents: List<RouteEvent> = emptyList()
    private var routeGuided = false
    private var restoreAir: PleosVehicleSource.RestoreState? = null
    private var question: Question? = null
    /** Started from the screen with no seat signal, on a car that reports neither occupancy nor belts. */
    private var manualRide = false
    private var rideUsedMode = false
    private var comfortAsked = false
    /** The introduction plays once per ride: turning the mode off and on again only confirms it. */
    private var introPlayed = false
    private var introJob: Job? = null
    private var started = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // While the demo drives, the car's own signals (a parked car reporting 0 km/h many times a second) would
    // interleave with the demo's and turn every step into a hard stop or start.
    private val vehicleSource = PleosVehicleSource(context, { if (!demo.running) onVehicleSignal(it) }) { status("차량", it) }
    private val carApi = CarApiSource(
        context,
        onSpeed = { speed -> if (!demo.running) updateVehicle { copy(speedMps = speed) } },
        onGear = { gear -> if (!demo.running) updateVehicle { copy(gear = gear) } },
        onRegen = { s, level -> updateVehicle { copy(regenState = s, regenLevel = level) } },
        onRestriction = { restricted -> _state.update { it.copy(drivingRestricted = restricted) } },
        onStatus = { status("AAOS", it) },
    )
    private val navi = NaviSource(context, { if (!demo.running) onRouteEvents(it) }) { status("내비", it) }
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
        onPassenger = { on -> updateVehicle { copy(passengerOnBoard = on) } },
        onAnswer = ::handle,
        awaitPrimingQuestion = { withTimeoutOrNull(DEMO_QUESTION_WAIT_MS) { _state.first { it.askPriming && it.intro == null } } },
        onFinished = { _state.update { it.copy(demoRunning = false) } },
    )

    fun start() {
        if (started) return
        started = true
        val profiles = store.load()
        val active = store.activeProfileId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
        _state.update { it.copy(profiles = profiles, activeProfileId = active, balanceBoostDb = store.balanceBoostDb) }
        applyProfileConfig()
        refreshReport()
        vehicleSource.start()
        carApi.start()
        navi.start()
        location.start()
        voice.start()
        // Recognize the driver from the seat once the vehicle service is up (corporate taxis change drivers between shifts).
        scope.launch {
            delay(2500)
            vehicleSource.readDriverSeat { foreAft, height ->
                val match = DriverIdentifier.match(_state.value.profiles, foreAft, height)
                if (match != null && match.id != _state.value.activeProfileId) {
                    selectProfile(match.id)
                    voice.speak("${match.name}님, 반가워요.")
                }
            }
        }
        // Seats and the clock can change with no vehicle signal arriving: an alighting hold, a new working day.
        scope.launch {
            while (true) {
                delay(1000)
                checkRide()
                if (DayReports.dayStart(System.currentTimeMillis()) != _state.value.dayReport.dayStartMs) refreshReport()
            }
        }
    }

    fun onPermissionsGranted() {
        carApi.subscribeSpeed()
        carApi.readRegen()
    }

    // ---- comfort mode ----

    fun setMode(on: Boolean) {
        if (_state.value.modeOn == on) return
        if (on) {
            turnModeOn()
        } else {
            turnModeOff()
            if (manualRide) endRide()
            voice.speak("편안한 승차 모드를 끌게요.")
        }
    }

    private fun turnModeOn() {
        question = null
        _state.update { it.copy(modeOn = true, suggestMode = false, askPriming = false) }
        cueEngine.reset()
        vehicleSource.applyFreshAir { restoreAir = it }
        if (recorder == null) {
            manualRide = true
            startRide()
        }
        rideUsedMode = true
        if (introPlayed) {
            voice.speak("편안한 승차 모드를 다시 켰어요.")
        } else {
            introPlayed = true
            playIntro()
        }
    }

    /**
     * Plays each cue right after its name, so the passenger knows the sounds before the first turn, then offers the
     * 1-minute tone while the car still stands. Unknown (no signals) counts as standing, for the demo.
     */
    private fun playIntro() {
        stopIntro()
        introJob = scope.launch {
            val volume = _state.value.activeProfile?.cueVolume ?: 0.6f
            _state.update { it.copy(intro = IntroPart.CUES) }
            for (step in RideIntro.cues) when (step) {
                is RideIntro.Step.Say -> {
                    _state.update { it.copy(introCaption = step.text) }
                    voice.say(step.text)
                }
                is RideIntro.Step.Chime -> {
                    // Centered: on the way a turn cue leans toward the turn, but here both ears should hear it.
                    audio.playCue(Cue(step.kind, 0f, "예고음 소개", System.currentTimeMillis()), volume)
                    delay(ToneSynth.cueMs(step.kind) + RideIntro.CHIME_GAP_MS)
                }
            }
            val s = _state.value
            if (s.stopped != false && !s.rideMoved && s.primingSecondsLeft == null) {
                question = Question.PRIMING
                _state.update { it.copy(intro = IntroPart.PRIMING, introCaption = RideIntro.PRIMING_QUESTION, askPriming = true) }
                voice.sayAndListen(RideIntro.PRIMING_QUESTION)
            }
            ensureActive()
            _state.update { it.copy(intro = null, introCaption = null) }
        }
    }

    private fun stopIntro() {
        introJob?.cancel()
        introJob = null
        if (_state.value.intro != null) _state.update { it.copy(intro = null, introCaption = null) }
    }

    private fun turnModeOff() {
        question = null
        stopIntro()
        _state.update { it.copy(modeOn = false, askPriming = false, askComfort = false) }
        audio.stopPriming()
        restoreAir?.let(vehicleSource::restoreAir)
        restoreAir = null
    }

    private fun ask(q: Question, text: String) {
        question = q
        voice.ask(text)
    }

    fun startPriming() {
        val s = _state.value
        if (s.primingSecondsLeft != null) return
        // Safety: never while the car is known to be moving. Unknown (no gear or speed signal) is allowed,
        // matching the button state, so the demo works without vehicle signals.
        // Once under way it is too late too: Gu et al. (2025) played the tone before the motion, not at lights.
        if (s.stopped == false || s.rideMoved) return
        val volume = s.activeProfile?.cueVolume ?: 0.6f
        val (left, right) = PrimingBalance.gains(s.seat, s.balanceBoostDb)
        question = null
        stopIntro()
        // Set before starting: a refused audio focus calls onDone right away, which must be able to clear it.
        _state.update { it.copy(primingSecondsLeft = 60, askPriming = false) }
        audio.startPriming(
            volume,
            left,
            right,
            onTick = { sec -> scope.launch { _state.update { it.copy(primingSecondsLeft = sec) } } },
            onDone = { completed ->
                scope.launch {
                    _state.update { it.copy(primingSecondsLeft = null) }
                    if (completed) voice.speak("준비가 끝났어요. 편안한 이동 되세요.")
                }
            },
        )
    }

    fun stopPriming() = audio.stopPriming()

    /**
     * The passenger's answer at boarding. Gu et al. (2025) tested one unbroken minute right before the ride,
     * so the tone is offered once, while the car stands, and never replayed at lights on the way.
     */
    fun answerPriming(listen: Boolean) {
        question = null
        stopIntro()
        _state.update { it.copy(askPriming = false) }
        if (listen) startPriming() else voice.speak("알림음만 켤게요.")
    }

    /** Cycles the seat used for the left/right balance: sensors → left → middle → right → sensors. */
    fun cycleSeat() {
        val next = when (_state.value.seatOverride) {
            null -> SeatSide.LEFT
            SeatSide.LEFT -> SeatSide.CENTER
            SeatSide.CENTER -> SeatSide.RIGHT
            SeatSide.RIGHT -> null
        }
        _state.update { it.copy(seatOverride = next) }
    }

    /** Adjusts the far-side boost in 1 dB steps after measuring both ears with a sound level meter. */
    fun adjustBalance(deltaDb: Float) {
        val db = (_state.value.balanceBoostDb + deltaDb).coerceIn(0f, PrimingBalance.MAX_BOOST_DB)
        store.balanceBoostDb = db
        _state.update { it.copy(balanceBoostDb = db) }
    }

    fun dismissSuggestion() {
        question = null
        _state.update { it.copy(suggestMode = false) }
    }

    fun listenVoice() = voice.listen()

    // ---- demo ----

    fun toggleDemo(style: DemoStyle = DemoStyle.GENTLE) {
        if (demo.running) {
            demo.stop()
            onRouteEvents(emptyList())
            updateVehicle { copy(speedMps = 0f, gear = Gear.PARK, brakePressed = false, turnSignal = TurnSignal.OFF, steeringDeg = 0f, passengerOnBoard = false) }
            _state.update { it.copy(demoRunning = false) }
        } else {
            _state.update { it.copy(demoRunning = true, vehicle = VehicleSnapshot(timestampMs = System.currentTimeMillis())) }
            demo.start(style)
        }
    }

    // ---- profiles ----

    fun selectProfile(id: String) {
        store.activeProfileId = id
        _state.update { it.copy(activeProfileId = id) }
        applyProfileConfig()
        refreshReport()
    }

    fun addProfile(name: String) {
        val profile = DriverProfile(id = "driver-${System.currentTimeMillis()}", name = name.ifBlank { "기사 ${_state.value.profiles.size + 1}" })
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

    /** The passenger's comfort score (1-5). Kept with the ride until they get out. */
    fun submitComfort(score: Int) {
        if (!_state.value.askComfort) return
        question = null
        _state.update { it.copy(askComfort = false, rideComfort = score) }
        voice.speak("감사합니다.")
    }

    fun dismissComfort() {
        question = null
        _state.update { it.copy(askComfort = false) }
    }

    /** Reads the day's tip. Never with a passenger aboard: being coached in front of a customer helps nobody. */
    fun speakCoaching() {
        val s = _state.value
        if (s.vehicle.passengerOnBoard) return
        s.dayReport.tip?.let { voice.speak(CoachingEngine.spoken(it)) }
    }

    private fun saveProfiles(profiles: List<DriverProfile>) {
        store.save(profiles)
        _state.update { it.copy(profiles = profiles) }
    }

    private fun applyProfileConfig() {
        _state.value.activeProfile?.let { cueEngine.updateConfig(ProfileTuner.cueConfig(it, routeGuided)) }
    }

    private fun refreshReport() {
        val profile = _state.value.activeProfile ?: return
        _state.update { it.copy(dayReport = DayReports.build(profile, System.currentTimeMillis())) }
    }

    // ---- rides ----

    private fun checkRide() {
        val s = _state.value
        when (rideTracker.update(s.vehicle.passengerOnBoard, s.stopped, System.currentTimeMillis())) {
            RideEvent.BOARDED -> onBoarded()
            RideEvent.ALIGHTED -> endRide()
            null -> Unit
        }
    }

    private fun onBoarded() {
        manualRide = false
        startRide()
        if (_state.value.modeOn) return
        _state.update { it.copy(suggestMode = true) }
        ask(Question.RIDE_MODE, "멀미가 걱정되시면 편안한 승차 모드를 켜 드릴까요? 네, 아니요로 말씀해 주세요.")
    }

    private fun startRide() {
        if (recorder != null) return
        recorder = TripRecorder(System.currentTimeMillis()).also { it.onRouteEvents(routeEvents) }
        rideUsedMode = false
        comfortAsked = false
        introPlayed = false
        cueEngine.reset()
        _state.update { it.copy(rideActive = true, rideMoved = false, rideComfort = null) }
    }

    private fun endRide() {
        val rec = recorder ?: return
        recorder = null
        manualRide = false
        val comfort = _state.value.rideComfort
        // Silent: the passenger is gone, and the next one starts from the car's own air settings.
        if (_state.value.modeOn) turnModeOff()
        question = null
        _state.update { it.copy(rideActive = false, rideMoved = false, suggestMode = false, askPriming = false, askComfort = false, rideComfort = null) }
        val stats = rec.finish()
        if (stats.durationSec < MIN_RIDE_SEC || stats.distanceM < MIN_RIDE_M) return
        val profile = _state.value.activeProfile ?: return
        val tuned = ProfileTuner.afterTrip(profile, TripRecord(System.currentTimeMillis(), stats, comfort, rideUsedMode))
        saveProfiles(_state.value.profiles.map { if (it.id == tuned.id) tuned else it })
        applyProfileConfig()
        refreshReport()
    }

    private fun askComfortOnce() {
        if (!_state.value.modeOn || comfortAsked) return
        comfortAsked = true
        _state.update { it.copy(askComfort = true) }
        ask(Question.COMFORT, "오늘 승차는 편안하셨나요? 1점부터 5점까지 말씀해 주세요.")
    }

    // ---- signal handling ----

    private fun onVehicleSignal(signal: PleosVehicleSource.VehicleSignal) {
        when (signal) {
            is PleosVehicleSource.VehicleSignal.Brake -> updateVehicle { copy(brakePressed = signal.pressed) }
            is PleosVehicleSource.VehicleSignal.Signal -> updateVehicle { copy(turnSignal = signal.state) }
            is PleosVehicleSource.VehicleSignal.Steering -> updateVehicle(log = false) { copy(steeringDeg = signal.degrees) }
            is PleosVehicleSource.VehicleSignal.PassengerSeats -> {
                _state.update { it.copy(detectedSeat = PrimingBalance.side(signal.left, signal.center, signal.right, signal.front)) }
                updateVehicle { copy(passengerOnBoard = signal.occupied) }
            }
        }
    }

    private fun updateVehicle(log: Boolean = true, change: VehicleSnapshot.() -> VehicleSnapshot) {
        scope.launch {
            val snapshot = _state.value.vehicle.change().copy(timestampMs = System.currentTimeMillis())
            val stopped = stopDetector.update(snapshot)
            _state.update { it.copy(vehicle = snapshot, stopped = stopped) }
            checkRide()
            // The 100Hz tone plays only while the car stands still: fade it out as soon as the car moves,
            // and drop unanswered boarding questions, since nobody can act on them any more.
            if (stopped == false) {
                if (recorder != null && !_state.value.rideMoved) _state.update { it.copy(rideMoved = true) }
                if (_state.value.primingSecondsLeft != null) audio.stopPriming()
                if (question == Question.RIDE_MODE || question == Question.PRIMING) question = null
                // The cue sounds may finish on the way; the offer of the tone may not.
                if (_state.value.intro == IntroPart.PRIMING) stopIntro()
                if (_state.value.askPriming || _state.value.suggestMode) _state.update { it.copy(askPriming = false, suggestMode = false) }
            }
            if (log) handlePark(snapshot)
            recorder?.onVehicle(snapshot)
            if (_state.value.modeOn) play(cueEngine.onVehicle(snapshot))
        }
    }

    /** Parking after driving means the destination, even with no route guidance to announce it. */
    private fun handlePark(v: VehicleSnapshot) {
        if (v.gear != Gear.PARK || recorder == null || !_state.value.rideMoved) return
        if (manualRide) endRide() else askComfortOnce()
    }

    private fun onRouteEvents(events: List<RouteEvent>) {
        scope.launch {
            routeEvents = events
            if (routeGuided != events.isNotEmpty()) {
                routeGuided = events.isNotEmpty()
                applyProfileConfig()
            }
            _state.update { it.copy(nextEvent = events.minByOrNull { e -> e.distanceM }) }
            recorder?.onRouteEvents(events)
            if (!_state.value.modeOn) return@launch
            if (_state.value.rideMoved && events.any { it.kind == RouteEventKind.ARRIVE && it.distanceM <= ASK_COMFORT_WITHIN_M }) askComfortOnce()
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

    private fun onVoiceText(text: String) {
        scope.launch { VoiceCommandParser.parse(text)?.let(::handle) }
    }

    private fun handle(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.ModeOn -> setMode(true)
            VoiceCommand.ModeOff -> setMode(false)
            VoiceCommand.StartPriming -> startPriming()
            VoiceCommand.CuesOnly -> answerPriming(listen = false)
            VoiceCommand.Yes -> when (question) {
                Question.RIDE_MODE -> setMode(true)
                Question.PRIMING -> answerPriming(listen = true)
                else -> Unit
            }
            VoiceCommand.No -> when (question) {
                Question.RIDE_MODE -> dismissSuggestion()
                Question.PRIMING -> answerPriming(listen = false)
                Question.COMFORT -> dismissComfort()
                null -> Unit
            }
            is VoiceCommand.Score -> if (question == Question.COMFORT) submitComfort(cmd.value)
            is VoiceCommand.SwitchDriver -> _state.value.profiles.firstOrNull { cmd.name in it.name }?.let {
                selectProfile(it.id)
                voice.speak("${it.name}님 프로필로 바꿨어요.")
            }
        }
    }

    private fun status(source: String, message: String) {
        scope.launch { _state.update { it.copy(sources = it.sources + (source to message)) } }
    }

    fun shutdown() {
        demo.stop()
        stopIntro()
        audio.release()
        vehicleSource.stop()
        carApi.stop()
        navi.stop()
        location.stop()
        voice.stop()
        started = false
    }

    private companion object {
        const val MIN_RIDE_SEC = 20
        const val MIN_RIDE_M = 30
        const val ASK_COMFORT_WITHIN_M = 150.0
        const val DEMO_QUESTION_WAIT_MS = 20_000L
    }
}
