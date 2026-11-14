package com.noisetrace.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import com.noisetrace.audio.CabinRecorder
import com.noisetrace.audio.ClipPlayer
import com.noisetrace.core.CapturePipeline
import com.noisetrace.core.CapturedClip
import com.noisetrace.core.Channel
import com.noisetrace.core.ContextBuilder
import com.noisetrace.core.DemoCabinAudio
import com.noisetrace.core.DrivingExposure
import com.noisetrace.core.EventFactory
import com.noisetrace.core.Gear
import com.noisetrace.core.HazardAhead
import com.noisetrace.core.HazardTracker
import com.noisetrace.core.DayLevels
import com.noisetrace.core.MonthlyReport
import com.noisetrace.core.MonthlyReports
import com.noisetrace.core.NoiseDetector
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoisePattern
import com.noisetrace.core.NoiseType
import com.noisetrace.core.PatternFinder
import com.noisetrace.core.SampleHistory
import com.noisetrace.core.Sensitivity
import com.noisetrace.core.Spot
import com.noisetrace.core.StopDetector
import com.noisetrace.core.StoragePolicy
import com.noisetrace.core.TriggerKind
import com.noisetrace.core.VehicleSnapshot
import com.noisetrace.core.VoiceCommand
import com.noisetrace.core.VoiceCommandParser
import com.noisetrace.core.label
import com.noisetrace.data.EventStore
import com.noisetrace.vehicle.CarApiSource
import com.noisetrace.vehicle.LocationSource
import com.noisetrace.vehicle.NaviSource
import com.noisetrace.vehicle.PleosVehicleSource
import com.noisetrace.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The voice questions after a save, one at a time. */
enum class TagStep { SAME, WHERE, KIND }

data class TagQuestion(val eventId: String, val step: TagStep, val text: String, val askedAtMs: Long)

/** A save in progress. [audio] is false for a save without the microphone (vehicle state only). */
data class CaptureStatus(val kind: TriggerKind, val secondsLeft: Int, val audio: Boolean = true)

data class Playback(val eventId: String, val positionMs: Long)

data class UiState(
    val trackingOn: Boolean = false,
    val micOn: Boolean = false,
    val needMicPermission: Boolean = false,
    /** The owner agreed to the microphone notice; tracking cannot start without it. */
    val micConsented: Boolean = false,
    val showConsent: Boolean = false,
    val autoDetect: Boolean = true,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val saveLocation: Boolean = false,
    /** Cabin level now against usual at this speed (dB); null while still learning or not listening. */
    val excessDb: Float? = null,
    val learning: Boolean = true,
    val capture: CaptureStatus? = null,
    val events: List<NoiseEvent> = emptyList(),
    val patterns: List<NoisePattern> = emptyList(),
    /** Last 30 days against the 30 before: saves, the main pattern, and how the usual sound at each speed moved. */
    val monthly: MonthlyReport? = null,
    val exposure: DrivingExposure = DrivingExposure(),
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val stopped: Boolean? = null,
    val drivingRestricted: Boolean = false,
    val question: TagQuestion? = null,
    val mechanicMode: Boolean = false,
    val selectedId: String? = null,
    val playback: Playback? = null,
    val demoRunning: Boolean = false,
    val sources: Map<String, String> = emptyMap(),
    val message: String? = null,
) {
    val selected: NoiseEvent? get() = events.firstOrNull { it.id == selectedId } ?: events.firstOrNull()
    val untagged: List<NoiseEvent> get() = events.filter { !it.tagged }
}

/**
 * Wires the car, the microphone, the voice and storage together. Lives as long as the app process;
 * [NoiseTraceService] keeps the process in the foreground while tracking.
 *
 * Flow: the microphone fills a 20-second memory → a press or the detector starts a capture → 10 more seconds →
 * the clip and the vehicle trace around it become a [NoiseEvent] → the app asks where and what it was →
 * repeated events of one type become a [NoisePattern] for the mechanic.
 */
class NoiseTraceController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = EventStore(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val stopDetector = StopDetector()
    private val hazards = HazardTracker()
    private val trace = ArrayDeque<VehicleSnapshot>()
    /** What the app works with: the real car, or the demo car while the demo drives. */
    private var vehicle = VehicleSnapshot()
    /** The real car, kept current during the demo so the app returns to it afterwards. */
    private var realVehicle = VehicleSnapshot()
    private var exposure = DrivingExposure()
    private var levelHistory: List<DayLevels> = emptyList()
    private var questionJob: Job? = null
    /** End of a save without the microphone, while it collects the 10 s after the press. */
    private var vehicleOnlyUntilMs: Long? = null
    private var started = false
    @Volatile private var musicActive = false
    @Volatile private var demoRunning = false

    private val realPipeline = CapturePipeline(detector = NoiseDetector())
    private var demoAudio: DemoCabinAudio? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val recorder = CabinRecorder(context, onClip = { clip, demo -> scope.launch { onClip(clip, demo) } }) { status("마이크", it) }
    private val player = ClipPlayer(context)
    private val voice = VoiceController(context, ::onVoiceText) { status("음성", it) }

    // The demo drives the car itself; real signals (a parked emulator reporting 0 km/h) would interleave with it.
    private val vehicleSource = PleosVehicleSource(context, ::onVehicleSignal) { status("차량", it) }
    private val carApi = CarApiSource(
        context,
        onSpeed = { v -> updateVehicle { copy(speedMps = v) } },
        onGear = { g -> updateVehicle { copy(gear = g) } },
        onOutsideTemp = { t -> updateVehicle { copy(outsideTempC = t) } },
        onRestriction = { r -> _state.update { it.copy(drivingRestricted = r) } },
        onStatus = { status("AAOS", it) },
    )
    private val navi = NaviSource(
        context,
        onHazards = { if (!demoRunning) onHazards(it) },
        onSpeedLimit = { l -> updateVehicle { copy(speedLimitKmh = l) } },
        onAddress = { a -> updateVehicle { copy(address = a) } },
        onStatus = { status("내비", it) },
    )
    private val location = LocationSource(context, { lat, lon, alt ->
        updateVehicle { copy(latitude = lat, longitude = lon, altitudeM = alt, locationAtMs = System.currentTimeMillis()) }
    }) { status("위치", it) }
    private val demo = DemoDrive(scope, this)

    fun start() {
        if (started) return
        started = true
        store.baseline?.let { realPipeline.detector.baseline.import(it) }
        realPipeline.autoDetect = store.autoDetect
        realPipeline.detector.sensitivity = store.sensitivity
        exposure = store.exposure
        levelHistory = store.levelHistory
        // Tracking switched on before the consent notice existed does not count as consent.
        if (store.trackingOn && store.micConsentAtMs == null) store.trackingOn = false
        _state.update {
            it.copy(
                trackingOn = store.trackingOn, autoDetect = store.autoDetect, sensitivity = store.sensitivity,
                saveLocation = store.saveLocation, exposure = exposure, micConsented = store.micConsentAtMs != null,
            )
        }
        recorder.suppressed = ::suppressed
        scope.launch {
            val events = withContext(Dispatchers.IO) { store.loadAll() }
            setEvents(events)
        }
        vehicleSource.start()
        carApi.start()
        navi.start()
        location.start()
        voice.start()
        if (store.trackingOn) startMic()
        // 10 Hz: the vehicle trace that becomes the context of each noise, and the capture countdown.
        scope.launch {
            var tick = 0
            while (true) {
                delay(100)
                sample()
                if (++tick % 5 == 0) refreshLive()
                if (tick % 10 == 0) countExposure()
                if (tick % 600 == 0) persistLearning()
            }
        }
    }

    fun onPermissionsGranted() {
        carApi.subscribeSpeed()
        val asked = _state.value.needMicPermission
        _state.update { it.copy(needMicPermission = false) }
        if (!store.trackingOn || recorder.active || demoRunning) return
        if (micGranted()) {
            startMic()
        } else if (asked) {
            setTracking(false)
            message("마이크 권한이 없어 녹음 없이 써요. 버튼을 누르면 차량 상태만 남겨요.")
        }
    }

    private fun micGranted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- consent ----

    /** The tracking switch. Turning it on shows the notice first; the OS permission comes only after agreeing. */
    fun requestTracking(on: Boolean) {
        if (on && store.micConsentAtMs == null) {
            _state.update { it.copy(showConsent = true) }
            return
        }
        setTracking(on)
    }

    fun acceptConsent(saveLocation: Boolean) {
        store.micConsentAtMs = now()
        setSaveLocation(saveLocation)
        _state.update { it.copy(showConsent = false, micConsented = true) }
        setTracking(true)
    }

    /** "녹음 없이 쓰기": the button keeps working and saves the car's state; no microphone, no automatic saves. */
    fun declineConsent() {
        _state.update { it.copy(showConsent = false) }
        message("녹음 없이 써요. 버튼을 누르면 그때의 차량 상태만 남겨요.")
    }

    fun dismissConsent() = _state.update { it.copy(showConsent = false) }

    /**
     * Withdrawing consent turns the microphone off and deletes every recording made under it. The vehicle-state part
     * of each record stays: it holds no sound. Demo records keep their synthetic audio.
     */
    fun withdrawConsent() {
        setTracking(false)
        store.micConsentAtMs = null
        player.stop()
        val recorded = _state.value.events.filter { it.hasAudio && !it.demo }
        val stripped = recorded.associate { it.id to it.copy(audioFile = null, audioBytes = 0) }
        scope.launch(Dispatchers.IO) {
            recorded.forEach(store::deleteAudio)
            stripped.values.forEach(store::update)
        }
        setEvents(_state.value.events.map { stripped[it.id] ?: it })
        _state.update { it.copy(micConsented = false, playback = null) }
        message("동의를 철회했어요. 녹음 파일 ${recorded.size}개를 지웠어요. 차량 상태 기록은 남아 있어요.")
    }

    // ---- tracking ----

    fun setTracking(on: Boolean) {
        store.trackingOn = on
        _state.update { it.copy(trackingOn = on) }
        if (on) startMic() else {
            if (!demoRunning) recorder.stop()
            status("마이크", "꺼짐 · 버튼을 누르면 녹음 없이 차량 상태만 저장")
            _state.update { it.copy(micOn = false, capture = null, excessDb = null) }
            persistLearning()
        }
        NoiseTraceService.start(context)
    }

    private fun startMic() {
        if (demoRunning) return
        if (!micGranted()) {
            _state.update { it.copy(needMicPermission = true) }
            return
        }
        recorder.startMic(realPipeline)
        _state.update { it.copy(micOn = true) }
    }

    fun setAutoDetect(on: Boolean) {
        store.autoDetect = on
        realPipeline.autoDetect = on
        _state.update { it.copy(autoDetect = on) }
    }

    fun setSensitivity(s: Sensitivity) {
        store.sensitivity = s
        realPipeline.detector.sensitivity = s
        _state.update { it.copy(sensitivity = s) }
    }

    fun setSaveLocation(on: Boolean) {
        store.saveLocation = on
        _state.update { it.copy(saveLocation = on) }
    }

    /** Never while music plays, the app talks, or a clip plays back: none of them is the car. */
    private fun suppressed(): Boolean =
        !demoRunning && (musicActive || voice.busy || player.playing || vehicle.windowOpen == true)

    // ---- saving ----

    /**
     * "방금 소리 저장": keeps the last 20 s and the next 10 s. Without the microphone (tracking off, no consent or no
     * permission) the button still works and keeps the car's state over the same 30 s.
     */
    fun saveNow() {
        val pipeline = recorder.pipeline
        if (recorder.active && pipeline != null) {
            pipeline.requestManual(now())
            message("방금 소리를 저장하고 있어요. 10초만 더 들을게요.")
        } else {
            saveVehicleOnly()
        }
    }

    private fun saveVehicleOnly() {
        if (vehicleOnlyUntilMs != null) return
        val pressedAt = now()
        vehicleOnlyUntilMs = pressedAt + CapturePipeline.POST_MS
        message("녹음 없이 지금의 차량 상태를 남기고 있어요. 10초 뒤 저장돼요.")
        scope.launch {
            delay(CapturePipeline.POST_MS + TRACE_SETTLE_MS)
            vehicleOnlyUntilMs = null
            val number = store.nextNumber()
            val built = EventFactory.buildVehicleOnly("ev-$pressedAt", number, pressedAt, trace.toList(), hazards.passes, demo = demoRunning)
            val event = if (store.saveLocation || demoRunning) built else withoutLocation(built)
            withContext(Dispatchers.IO) { store.save(event, null, 0) }
            Log.i(TAG, "saved #${event.number} without audio at ${event.context.speedKmh} km/h")
            setEvents(listOf(event) + _state.value.events)
            _state.update { it.copy(selectedId = event.id) }
            message("녹음 없이 차량 상태를 저장했어요 (#%02d)".format(event.number))
            askTags(event)
        }
    }

    private fun withoutLocation(e: NoiseEvent) = e.copy(context = e.context.copy(latitude = null, longitude = null, address = null))

    private suspend fun onClip(clip: CapturedClip, fromDemo: Boolean) {
        val traceCopy = trace.toList()
        val passes = hazards.passes
        val number = store.nextNumber()
        val id = "ev-${clip.savedAtMs}"
        // The demo's places are made up, so its reports keep them whatever the setting.
        val saveLocation = store.saveLocation || fromDemo
        val event = withContext(Dispatchers.Default) {
            val e = EventFactory.build(id, number, clip, traceCopy, passes, "$id.wav", demo = fromDemo)
            if (saveLocation) e else withoutLocation(e)
        }
        withContext(Dispatchers.IO) { store.save(event, clip.pcm, clip.sampleRate) }
        Log.i(TAG, "saved #${event.number} ${event.trigger} at ${event.context.speedKmh} km/h, excess ${event.excessDb}")
        setEvents(listOf(event) + _state.value.events)
        _state.update { it.copy(selectedId = event.id) }
        message(if (event.trigger == TriggerKind.AUTO) "평소보다 큰 소리를 자동으로 저장했어요 (#%02d)".format(event.number) else "저장했어요 (#%02d)".format(event.number))
        askTags(event)
    }

    private fun setEvents(events: List<NoiseEvent>) {
        val sorted = events.distinctBy { it.id }.sortedByDescending { it.noiseAtMs }
        val drop = StoragePolicy.toDelete(sorted).toSet()
        if (drop.isNotEmpty()) scope.launch(Dispatchers.IO) { sorted.filter { it.id in drop }.forEach(store::delete) }
        val kept = sorted.filter { it.id !in drop }
        _state.update { it.copy(events = kept, patterns = PatternFinder.find(kept, exposure), monthly = MonthlyReports.build(kept, exposure, levelHistory, now())) }
    }

    private fun changeEvent(id: String, change: NoiseEvent.() -> NoiseEvent) {
        val e = _state.value.events.firstOrNull { it.id == id } ?: return
        val updated = e.change()
        scope.launch(Dispatchers.IO) { store.update(updated) }
        setEvents(_state.value.events.map { if (it.id == id) updated else it })
    }

    fun deleteEvent(id: String) {
        val e = _state.value.events.firstOrNull { it.id == id } ?: return
        if (_state.value.playback?.eventId == id) player.stop()
        if (_state.value.question?.eventId == id) clearQuestion()
        scope.launch(Dispatchers.IO) { store.delete(e) }
        setEvents(_state.value.events.filter { it.id != id })
    }

    fun confirmEvent(id: String) = changeEvent(id) { copy(confirmed = true) }

    fun setTags(id: String, type: NoiseType?, spots: Set<Spot>) = changeEvent(id) {
        copy(userType = type ?: userType, spots = spots, confirmed = true)
    }

    // ---- voice questions after a save ----

    private fun askTags(e: NoiseEvent) {
        if (e.tagged) return
        val prev = _state.value.events.firstOrNull { it.id != e.id && it.tagged && it.demo == e.demo && e.noiseAtMs - it.noiseAtMs in 0..SAME_WINDOW_MS }
        if (prev != null) {
            ask(TagQuestion(e.id, TagStep.SAME, "방금 소리, 아까 저장한 ${prev.userType!!.label} 소리와 같은가요?", now()))
        } else {
            ask(TagQuestion(e.id, TagStep.WHERE, "저장했어요. 어디서 났나요? 앞, 뒤, 왼쪽, 오른쪽으로 말씀해 주세요.", now()))
        }
    }

    private fun ask(q: TagQuestion) {
        _state.update { it.copy(question = q) }
        voice.ask(q.text)
        questionJob?.cancel()
        // An unanswered question goes away; the event stays in the list to tag later on the screen.
        questionJob = scope.launch {
            delay(QUESTION_TIMEOUT_MS)
            if (_state.value.question == q) clearQuestion()
        }
    }

    private fun clearQuestion() {
        questionJob?.cancel()
        _state.update { it.copy(question = null) }
    }

    fun answerSame(same: Boolean) {
        val q = _state.value.question?.takeIf { it.step == TagStep.SAME } ?: return
        if (same) {
            val e = _state.value.events.firstOrNull { it.id == q.eventId } ?: return clearQuestion()
            val prev = _state.value.events.firstOrNull { it.id != e.id && it.tagged && it.demo == e.demo && e.noiseAtMs - it.noiseAtMs in 0..SAME_WINDOW_MS }
            if (prev != null) setTags(e.id, prev.userType, prev.spots)
            clearQuestion()
            voice.speak("같은 소리로 기록했어요.")
        } else {
            ask(TagQuestion(q.eventId, TagStep.WHERE, "어디서 났나요? 앞, 뒤, 왼쪽, 오른쪽으로 말씀해 주세요.", now()))
        }
    }

    fun answerWhere(spots: Set<Spot>) {
        val q = _state.value.question?.takeIf { it.step == TagStep.WHERE } ?: return
        changeEvent(q.eventId) { copy(spots = spots, confirmed = true) }
        ask(TagQuestion(q.eventId, TagStep.KIND, "어떤 소리였나요? 달그락, 삐걱, 웅웅 중에 말씀해 주세요.", now()))
    }

    fun answerKind(type: NoiseType) {
        val q = _state.value.question?.takeIf { it.step == TagStep.KIND } ?: return
        changeEvent(q.eventId) { copy(userType = type, confirmed = true) }
        clearQuestion()
        voice.speak("${type.label} 소리로 기록했어요.")
    }

    fun skipQuestion() = clearQuestion()

    fun listenVoice() = voice.listen()

    private fun onVoiceText(text: String) {
        scope.launch {
            val expect = when (_state.value.question?.step) {
                TagStep.WHERE -> VoiceCommandParser.Expect.WHERE
                TagStep.KIND -> VoiceCommandParser.Expect.KIND
                TagStep.SAME -> VoiceCommandParser.Expect.YES_NO
                null -> VoiceCommandParser.Expect.ANY
            }
            VoiceCommandParser.parse(text, expect)?.let(::handle)
        }
    }

    /** Spoken commands and answers; the demo drive answers through here too. */
    fun handle(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.SaveNoise -> saveNow()
            VoiceCommand.Yes -> answerSame(true)
            VoiceCommand.No -> answerSame(false)
            is VoiceCommand.Where -> answerWhere(cmd.spots)
            is VoiceCommand.Kind -> answerKind(cmd.type)
        }
    }

    // ---- mechanic mode ----

    fun setMechanicMode(on: Boolean) {
        if (!on) player.stop()
        _state.update { it.copy(mechanicMode = on, selectedId = it.selectedId ?: it.events.firstOrNull()?.id) }
    }

    fun select(id: String) {
        _state.update { it.copy(selectedId = id) }
    }

    /** Plays the clip from 5 s before the noise, or from the start. Only while the car stands still. */
    fun play(id: String, fromStart: Boolean = false) {
        if (_state.value.stopped == false) return message("차를 세운 뒤 들을 수 있어요")
        val e = _state.value.events.firstOrNull { it.id == id } ?: return
        val file = e.audioFile?.let(store::audioFile)?.takeIf { it.exists() } ?: return message("녹음 파일이 없어요")
        val from = if (fromStart) 0L else (e.noiseOffsetMs - PLAY_LEAD_MS).coerceAtLeast(0)
        _state.update { it.copy(playback = Playback(id, from)) }
        player.play(file, from, onPosition = { pos -> scope.launch { _state.update { s -> if (s.playback?.eventId == id) s.copy(playback = Playback(id, pos)) else s } } }) {
            scope.launch { _state.update { s -> if (s.playback?.eventId == id) s.copy(playback = null) else s } }
        }
    }

    fun stopPlayback() = player.stop()

    // ---- demo ----

    fun toggleDemo() {
        if (demoRunning) demo.stop() else demo.start()
    }

    internal fun beginDemo(audio: DemoCabinAudio) {
        demoRunning = true
        demoAudio = audio
        clearQuestion()
        hazards.clear()
        trace.clear()
        setVehicle(VehicleSnapshot())
        // A fresh detector: the demo's synthetic cabin must not become this car's usual sound.
        val pipeline = CapturePipeline(detector = NoiseDetector(sensitivity = realPipeline.detector.sensitivity))
        recorder.startDemo(pipeline, audio)
        _state.update { it.copy(demoRunning = true, mechanicMode = false, micOn = false, vehicle = vehicle) }
    }

    internal fun endDemo() {
        demoRunning = false
        demoAudio = null
        recorder.stop()
        setVehicle(realVehicle)
        trace.clear()
        hazards.clear()
        _state.update { it.copy(demoRunning = false, capture = null, excessDb = null, vehicle = vehicle) }
        if (store.trackingOn) startMic()
    }

    internal fun demoVehicle(change: VehicleSnapshot.() -> VehicleSnapshot) {
        setVehicle(vehicle.change())
        demoAudio?.speedKmh = vehicle.speedKmh ?: 0f
    }

    internal fun demoHazards(list: List<HazardAhead>) = onHazards(list)

    internal fun demoSound(type: NoiseType) {
        demoAudio?.inject(type)
    }

    /** Adds a week of earlier saves so the pattern and mechanic views have something to show. */
    fun loadSamples() {
        scope.launch {
            val samples = withContext(Dispatchers.Default) { SampleHistory.build(System.currentTimeMillis(), 1) }
            withContext(Dispatchers.IO) {
                samples.forEach { s -> store.save(s.event.copy(audioFile = "${s.event.id}.wav"), s.pcm, CapturePipeline.SAMPLE_RATE) }
            }
            exposure = DrivingExposure(
                exposure.totalSec + SampleHistory.exposure.totalSec,
                (exposure.seconds.keys + SampleHistory.exposure.seconds.keys).associateWith { (exposure.seconds[it] ?: 0) + (SampleHistory.exposure.seconds[it] ?: 0) },
            )
            store.exposure = exposure
            // Real days win over the made-up ones for the same date.
            val real = levelHistory.filter { !it.demo }
            levelHistory = (MonthlyReports.sampleHistory(now()).filter { d -> real.none { it.day == d.day } } + real).sortedBy { it.day }
            store.levelHistory = levelHistory
            _state.update { it.copy(exposure = exposure) }
            // Number everything by date again, so #01 stays the oldest after a week is added before it.
            val all = (_state.value.events + samples.map { it.event.copy(audioFile = "${it.event.id}.wav") }).sortedBy { it.noiseAtMs }
            val renumbered = all.mapIndexed { i, e -> e.copy(number = i + 1) }
            val changed = renumbered.filterIndexed { i, e -> e.number != all[i].number || samples.any { it.event.id == e.id } }
            withContext(Dispatchers.IO) { changed.forEach(store::update) }
            store.setNumber(renumbered.size)
            setEvents(renumbered)
            message("지난 기록을 불러왔어요 (데모): 일주일치 소음 4건과 두 달치 평소 소리")
        }
    }

    fun clearAll() {
        player.stop()
        clearQuestion()
        scope.launch(Dispatchers.IO) { store.deleteAll() }
        exposure = DrivingExposure()
        levelHistory = emptyList()
        _state.update { it.copy(events = emptyList(), patterns = emptyList(), monthly = null, exposure = exposure, selectedId = null, playback = null) }
        message("기록을 모두 지웠어요")
    }

    // ---- vehicle ----

    private fun onVehicleSignal(signal: PleosVehicleSource.VehicleSignal) {
        when (signal) {
            is PleosVehicleSource.VehicleSignal.Brake -> updateVehicle { copy(brakePressed = signal.pressed) }
            is PleosVehicleSource.VehicleSignal.Signal -> updateVehicle { copy(turnSignal = signal.state) }
            is PleosVehicleSource.VehicleSignal.Steering -> updateVehicle { copy(steeringDeg = signal.degrees) }
            is PleosVehicleSource.VehicleSignal.Wiper -> updateVehicle { copy(wiperOn = signal.on) }
            is PleosVehicleSource.VehicleSignal.Ac -> updateVehicle { copy(acOn = signal.on) }
            is PleosVehicleSource.VehicleSignal.WindowOpen -> updateVehicle { copy(windowOpen = signal.open) }
            is PleosVehicleSource.VehicleSignal.Odometer -> updateVehicle { copy(odometerKm = signal.km) }
        }
    }

    /**
     * A real vehicle signal. Signals arrive at many hertz; the screen is refreshed from [sample] instead of on every one.
     * During the demo the real car is only remembered: its parked 0 km/h would interleave with the demo's speed.
     */
    private fun updateVehicle(change: VehicleSnapshot.() -> VehicleSnapshot) {
        realVehicle = realVehicle.change()
        if (!demoRunning) setVehicle(realVehicle)
    }

    private fun setVehicle(v: VehicleSnapshot) {
        vehicle = v.copy(timestampMs = now())
        recorder.speedKmh = vehicle.speedKmh
    }

    private fun onHazards(list: List<HazardAhead>) = hazards.update(list, now())

    private fun sample() {
        val snap = vehicle.copy(timestampMs = now())
        trace.addLast(snap)
        while (trace.size > TRACE_SIZE) trace.removeFirst()
        val stopped = stopDetector.update(snap)
        if (stopped != _state.value.stopped) _state.update { it.copy(stopped = stopped) }
        val pipeline = recorder.pipeline.takeIf { recorder.active }
        val left = pipeline?.captureLeftMs
        val vehicleLeft = vehicleOnlyUntilMs?.let { (it - now()).coerceAtLeast(0) }
        val capture = when {
            left != null -> CaptureStatus(pipeline.captureKind ?: TriggerKind.MANUAL, ((left + 999) / 1000).toInt())
            vehicleLeft != null -> CaptureStatus(TriggerKind.MANUAL, ((vehicleLeft + 999) / 1000).toInt(), audio = false)
            else -> null
        }
        if (capture != _state.value.capture) _state.update { it.copy(capture = capture) }
    }

    private fun refreshLive() {
        musicActive = runCatching { audioManager.isMusicActive && !player.playing }.getOrDefault(false)
        val pipeline = recorder.pipeline.takeIf { recorder.active }
        val frame = pipeline?.lastFrame
        val band = pipeline?.detector?.baseline?.band(vehicle.speedKmh) ?: 0
        val learning = pipeline == null || !pipeline.detector.baseline.mature(Channel.HIGH, band)
        _state.update {
            it.copy(
                vehicle = vehicle,
                excessDb = frame?.takeIf { f -> !f.suppressed }?.excessDb,
                learning = learning,
                micOn = recorder.active && !demoRunning,
            )
        }
        val q = _state.value.question
        if (q != null && now() - q.askedAtMs > QUESTION_TIMEOUT_MS) clearQuestion()
    }

    /** One second of driving under the current conditions, for telling rare conditions from everyday ones. */
    private fun countExposure() {
        if (demoRunning) return
        val v = vehicle
        val driving = (v.gear != Gear.PARK && v.gear != Gear.UNKNOWN) || (v.speedKmh ?: 0f) >= 1f
        if (!driving) return
        val conditions = ContextBuilder.at(trace.toList(), hazards.passes, now()).conditions()
        exposure = exposure.plus(conditions, 1)
        if (exposure.totalSec % 60 == 0L) {
            store.exposure = exposure
            _state.update { it.copy(exposure = exposure, patterns = PatternFinder.find(it.events, exposure)) }
        }
    }

    /** Saves what the detector learned, and today's usual level per speed for the monthly report. */
    private fun persistLearning() {
        store.baseline = realPipeline.detector.baseline.export()
        store.exposure = exposure
        if (demoRunning || !recorder.active) return
        val today = MonthlyReports.today(realPipeline.detector.baseline, now()) ?: return
        levelHistory = MonthlyReports.merge(levelHistory, today, now())
        store.levelHistory = levelHistory
    }

    private fun message(text: String) {
        _state.update { it.copy(message = text) }
        scope.launch {
            delay(MESSAGE_MS)
            _state.update { if (it.message == text) it.copy(message = null) else it }
        }
    }

    private fun status(source: String, message: String) {
        scope.launch { _state.update { it.copy(sources = it.sources + (source to message)) } }
    }

    private fun now() = System.currentTimeMillis()

    fun shutdown() {
        demo.stop()
        persistLearning()
        recorder.stop()
        player.stop()
        vehicleSource.stop()
        carApi.stop()
        navi.stop()
        location.stop()
        voice.stop()
        started = false
    }

    private companion object {
        const val TAG = "NoiseTrace"
        /** 60 s at 10 Hz: a press up to 20 s after the noise, 10 s more after it, and the slope lookback before. */
        const val TRACE_SIZE = 600
        const val SAME_WINDOW_MS = 30 * 60_000L
        const val QUESTION_TIMEOUT_MS = 60_000L
        const val PLAY_LEAD_MS = 5_000L
        const val MESSAGE_MS = 4_000L
        /** Lets the last trace sample after the 10 s window arrive before the record is built. */
        const val TRACE_SETTLE_MS = 200L
    }
}
