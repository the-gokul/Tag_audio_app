package com.nordic.tagmobile

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.audio.AudioChunkAssembler
import com.nordic.tagmobile.audio.WavPcmWriter
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.databinding.ActivityMicLiveBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.protocol.AudioPacketParser
import com.nordic.tagmobile.protocol.Ble2Ascii
import com.nordic.tagmobile.protocol.Ble2AsciiParser
import com.nordic.tagmobile.protocol.Ble2AudioConfig
import com.nordic.tagmobile.protocol.Ble2Command
import com.nordic.tagmobile.protocol.Ble2GainState
import com.nordic.tagmobile.storage.RecordingStore
import com.nordic.tagmobile.storage.SessionManifestData
import com.nordic.tagmobile.storage.StorageGate
import java.io.File
import java.util.Locale

class MicLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicLiveBinding
    private val bleManager get() = TagApp.instance.bleManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null

    private var uiState = UiState.IDLE
    private var gain: Ble2GainState? = null
    private var audioConfig: Ble2AudioConfig? = null
    private var configFromTag = false
    private var deviceInfoLine: String? = null
    private var seekingGain = false

    private var assembler: AudioChunkAssembler? = null
    private var wavWriter: WavPcmWriter? = null
    private var sessionDir: File? = null
    private var sessionId: String = ""
    private var wavFile: File? = null
    private var startMs = 0L
    private var recordStartedElapsed = 0L
    @Volatile private var lastAudioMs = 0L
    private var finishAfterSave = false
    private var saving = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val durationRunnable = object : Runnable {
        override fun run() {
            if (uiState != UiState.RECORDING) return
            val totalSec = ((SystemClock.elapsedRealtime() - recordStartedElapsed) / 1000L).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            binding.recordDurationText.text = String.format(Locale.US, "%02d:%02d", mm, ss)
            audioHandler?.post { assembler?.tick(SystemClock.elapsedRealtime()) }
            if (lastAudioMs > 0L && SystemClock.elapsedRealtime() - lastAudioMs > STREAM_STALL_MS) {
                Toast.makeText(this@MicLiveActivity, R.string.mic_stream_timeout, Toast.LENGTH_LONG).show()
                requestStop(termination = "STREAM_TIMEOUT", qualityOverride = "WARNING")
                return
            }
            mainHandler.postDelayed(this, 200)
        }
    }

    private val startTimeout = Runnable {
        if (uiState == UiState.STARTING) {
            uiState = UiState.IDLE
            TagSession.recordingState = RecordingState.IDLE
            Toast.makeText(this, R.string.mic_start_timeout, Toast.LENGTH_LONG).show()
            renderRecordButton()
            setGainEnabled(true)
        }
    }

    private val stopTimeout = Runnable {
        if (uiState == UiState.STOPPING) {
            finalizeWav("STOP_TIMEOUT", "WARNING")
        }
    }

    private val bleListener = object : TagBleManager.Listener {
        override fun onReady(device: BluetoothDevice) {
            runOnUiThread {
                renderConnection()
                requestInit()
            }
        }

        override fun onDisconnected() {
            runOnUiThread { handleDisconnect() }
        }

        override fun onPacket(data: ByteArray) = Unit

        override fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MicLiveActivity, message, Toast.LENGTH_LONG).show()
                binding.statusBanner.text = message
                if (uiState == UiState.STOPPING && message.contains("Write failed")) {
                    mainHandler.removeCallbacks(stopTimeout)
                    finalizeWav(pendingTerm, pendingQuality ?: "WARNING")
                }
            }
        }

        override fun onAsciiLine(line: String) {
            runOnUiThread { handleAscii(line) }
        }

        override fun onAudioNotify(data: ByteArray) {
            audioHandler?.post { handleAudio(data) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pet = TagSession.userProfile.dogName.ifBlank { TagSession.userProfile.name }
        val tag = TagSession.connectedDevice?.name?.ifBlank { null } ?: "Tag_PDM"
        binding.micSubtitle.text = getString(R.string.record_pet_subtitle, pet.ifBlank { tag })

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.recordBtnContainer.setOnClickListener { onRecordClicked() }

        bindGainUi()
        renderConnection()
        setGainEnabled(true)
        refreshGainLabels()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when (uiState) {
                        UiState.RECORDING, UiState.STARTING -> {
                            finishAfterSave = true
                            requestStop("USER_BACK")
                        }
                        UiState.STOPPING, UiState.SAVING -> Unit
                        UiState.IDLE -> finish()
                    }
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        startAudioThread()
        bleManager.addListener(bleListener)
        if (bleManager.isTagReady && uiState == UiState.IDLE) requestInit()
        renderConnection()
    }

    override fun onStop() {
        super.onStop()
        if (uiState == UiState.IDLE) {
            bleManager.removeListener(bleListener)
            stopAudioThread()
        }
    }

    override fun onDestroy() {
        bleManager.removeListener(bleListener)
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        if (uiState == UiState.IDLE) {
            assembler?.stopAccepting()
            wavWriter?.abort()
            wavFile?.delete()
            sessionDir?.delete()
        }
        stopAudioThread()
        super.onDestroy()
    }

    private fun startAudioThread() {
        if (audioThread != null) return
        val t = HandlerThread("mic-audio")
        t.start()
        audioThread = t
        audioHandler = Handler(t.looper)
    }

    private fun stopAudioThread() {
        audioThread?.quitSafely()
        audioThread = null
        audioHandler = null
    }

    private fun requestInit() {
        bleManager.sendAscii(Ble2Command.GAIN_GET)
        bleManager.sendAscii(Ble2Command.AUDIO_CONFIG_GET)
        bleManager.sendAscii(Ble2Command.DEVICE_INFO_GET)
    }

    private fun onRecordClicked() {
        when (uiState) {
            UiState.IDLE -> requestStart()
            UiState.RECORDING -> requestStop("USER_STOP")
            UiState.STARTING, UiState.STOPPING, UiState.SAVING -> Unit
        }
    }

    private fun requestStart() {
        if (uiState != UiState.IDLE) return
        if (!bleManager.isTagReady) {
            Toast.makeText(this, R.string.tag_disconnected_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = audioConfig
        if (cfg != null && configFromTag && !cfg.isCompatible()) {
            Toast.makeText(this, R.string.mic_config_incompatible, Toast.LENGTH_LONG).show()
            return
        }
        val free = micStorageOk()
        if (!free) return

        val use = cfg ?: Ble2AudioConfig.fallback()
        audioConfig = use
        val sid = RecordingStore.makeSessionId()
        sessionId = sid
        val dir = RecordingStore.createSessionDir(this, sid)
        sessionDir = dir
        val wav = RecordingStore.sessionAudioFile(dir, sid)
        wavFile = wav

        val writer = WavPcmWriter(wav, use.pcmHz, use.channels, use.bits)
        wavWriter = writer
        val asm = AudioChunkAssembler { chunk ->
            writer.writePcmLe(chunk.pcm)
            binding.waveform.append(chunk.pcm)
            lastAudioMs = SystemClock.elapsedRealtime()
        }
        assembler = asm

        uiState = UiState.STARTING
        TagSession.recordingState = RecordingState.RECEIVING
        setGainEnabled(false)
        renderRecordButton()
        binding.statusBanner.text = getString(R.string.mic_starting)
        binding.waveform.reset()
        lastAudioMs = 0L
        audioHandler?.post {
            try {
                writer.open()
                asm.reset()
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, e.message ?: "WAV open failed", Toast.LENGTH_LONG).show()
                    abortStart()
                }
                return@post
            }
            mainHandler.post {
                if (uiState != UiState.STARTING) return@post
                bleManager.sendAscii(Ble2Command.START)
                mainHandler.postDelayed(startTimeout, CMD_TIMEOUT_MS)
            }
        }
    }

    private fun requestStop(termination: String, qualityOverride: String? = null) {
        if (uiState != UiState.RECORDING && uiState != UiState.STARTING) return
        mainHandler.removeCallbacks(startTimeout)
        pendingTerm = termination
        pendingQuality = qualityOverride
        uiState = UiState.STOPPING
        renderRecordButton()
        binding.statusBanner.text = getString(R.string.mic_stopping)
        bleManager.sendAscii(Ble2Command.STOP)
        mainHandler.postDelayed(stopTimeout, CMD_TIMEOUT_MS)
    }

    private var pendingTerm = "USER_STOP"
    private var pendingQuality: String? = null

    private fun handleAscii(line: String) {
        when (val msg = Ble2AsciiParser.parse(line)) {
            is Ble2Ascii.Pong -> {
                if (uiState == UiState.IDLE) {
                    binding.statusBanner.text = getString(R.string.mic_ready)
                }
            }
            is Ble2Ascii.Gain -> {
                gain = msg.state
                refreshGainLabels()
            }
            is Ble2Ascii.AudioConfig -> {
                audioConfig = msg.config
                configFromTag = true
                if (!msg.config.isCompatible()) {
                    binding.statusBanner.text = getString(R.string.mic_config_incompatible)
                }
            }
            is Ble2Ascii.DeviceInfo -> {
                deviceInfoLine = line
                TagSession.firmwareVersion = msg.info.fw.ifBlank { TagSession.firmwareVersion }
                val name = msg.info.name.ifBlank { TagSession.connectedDevice?.name ?: "Tag_PDM" }
                binding.micTitle.text = name
            }
            is Ble2Ascii.Ok -> {
                when {
                    msg.command.startsWith("START") && uiState == UiState.STARTING -> {
                        mainHandler.removeCallbacks(startTimeout)
                        enterRecording()
                    }
                    msg.command.startsWith("STOP") && uiState == UiState.STOPPING -> {
                        mainHandler.removeCallbacks(stopTimeout)
                        finalizeWav(pendingTerm, pendingQuality)
                    }
                    msg.command.startsWith("GAIN") -> bleManager.sendAscii(Ble2Command.GAIN_GET)
                }
            }
            is Ble2Ascii.Err -> {
                Toast.makeText(this, line, Toast.LENGTH_LONG).show()
                binding.statusBanner.text = line
                if (uiState == UiState.STARTING && line.contains("START")) {
                    mainHandler.removeCallbacks(startTimeout)
                    abortStart()
                } else if (uiState == UiState.STOPPING && line.contains("STOP")) {
                    mainHandler.removeCallbacks(stopTimeout)
                    finalizeWav(pendingTerm, pendingQuality ?: "WARNING")
                }
            }
            is Ble2Ascii.Status, is Ble2Ascii.Other -> Unit
        }
    }

    private fun enterRecording() {
        uiState = UiState.RECORDING
        TagSession.recordingState = RecordingState.RECEIVING
        startMs = System.currentTimeMillis()
        recordStartedElapsed = SystemClock.elapsedRealtime()
        lastAudioMs = SystemClock.elapsedRealtime()
        acquireWakeLock()
        binding.recordDurationText.visibility = View.VISIBLE
        binding.recordDurationText.text = "00:00"
        binding.statusBanner.text = getString(R.string.mic_recording)
        renderRecordButton()
        setGainEnabled(false)
        mainHandler.removeCallbacks(durationRunnable)
        mainHandler.post(durationRunnable)
        TagLogger.log(LogCategory.BLE, "MIC_START", sessionId)
    }

    private fun abortStart() {
        uiState = UiState.IDLE
        TagSession.recordingState = RecordingState.IDLE
        audioHandler?.post {
            assembler?.stopAccepting()
            wavWriter?.abort()
            wavFile?.delete()
            sessionDir?.delete()
        }
        assembler = null
        wavWriter = null
        setGainEnabled(true)
        renderRecordButton()
        releaseWakeLock()
    }

    private fun handleAudio(data: ByteArray) {
        if (uiState != UiState.RECORDING &&
            uiState != UiState.STARTING &&
            uiState != UiState.STOPPING
        ) {
            return
        }
        val frag = AudioPacketParser.parse(data)
        if (frag == null) {
            assembler?.stats?.let { it.invalidPackets++ }
            return
        }
        assembler?.offer(frag, SystemClock.elapsedRealtime())
    }

    private fun handleDisconnect() {
        renderConnection()
        if (uiState == UiState.RECORDING || uiState == UiState.STARTING || uiState == UiState.STOPPING) {
            Toast.makeText(this, R.string.mic_disconnect_saving, Toast.LENGTH_LONG).show()
            mainHandler.removeCallbacks(startTimeout)
            mainHandler.removeCallbacks(stopTimeout)
            audioHandler?.post { assembler?.stopAccepting() }
            finishAfterSave = true
            finalizeWav("BLE_DISCONNECT", "SESSION_LOSS")
        } else {
            binding.statusBanner.text = getString(R.string.disconnected)
        }
    }

    private fun finalizeWav(termination: String, qualityOverride: String?) {
        if (saving) return
        saving = true
        uiState = UiState.SAVING
        TagSession.recordingState = RecordingState.SAVING
        mainHandler.removeCallbacks(durationRunnable)
        audioHandler?.post {
            assembler?.stopAccepting()
            try {
                wavWriter?.finish()
            } catch (_: Exception) {
            }
            wavWriter = null
            mainHandler.post { saveSession(termination, qualityOverride) }
        }
    }

    private fun saveSession(termination: String, qualityOverride: String?) {
        val stats = assembler?.stats
        assembler = null
        val samples = stats?.completedChunks?.times(AudioPacketParser.CHUNK_SAMPLES) ?: 0
        val packets = stats?.receivedNotifies ?: 0
        val missing = stats?.missingPackets ?: 0
        val expected = (stats?.completedChunks ?: 0) + missing
        val missPct = if (expected > 0) 100.0 * missing / expected else 0.0
        val quality = qualityOverride ?: when {
            missing > 0 || (stats?.droppedIncomplete ?: 0) > 0 -> "WARNING"
            else -> "GOOD"
        }
        val endMs = System.currentTimeMillis()
        val wav = wavFile
        val wavOk = wav != null && wav.exists() && wav.length() > 44L
        val wavSize = wav?.takeIf { it.exists() }?.let { StorageGate.formatBytes(it.length()) } ?: "0 B"
        val profile = TagSession.userProfile
        val user = TagSession.appUser
        val pkgInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
        val logBody = buildString {
            appendLine("Tag microphone session")
            appendLine("session_id=$sessionId")
            appendLine("termination=$termination")
            appendLine("quality=$quality")
            appendLine("packets=$packets completed=${stats?.completedChunks} missing=$missing")
            appendLine("duplicates=${stats?.duplicatePackets} invalid=${stats?.invalidPackets}")
            appendLine("ooo=${stats?.outOfOrderPackets} incomplete=${stats?.droppedIncomplete}")
            appendLine("missing_sample_percent=${"%.2f".format(Locale.US, missPct)}")
            appendLine("audio_config=$audioConfig")
            appendLine("gain=$gain")
            appendLine("device_info=${deviceInfoLine.orEmpty()}")
            appendLine("---")
            append(TagLogger.sessionSnapshot())
        }
        val cfg = audioConfig ?: Ble2AudioConfig.fallback()
        val manifest = SessionManifestData(
            sessionId = sessionId.ifBlank { RecordingStore.makeSessionId() },
            packetCount = packets,
            sampleCount = samples,
            parseFailures = stats?.invalidPackets ?: 0,
            statusDetail = "pcm ${cfg.pcmHz} Hz ${cfg.bits}-bit mono; miss=${"%.1f".format(Locale.US, missPct)}%",
            hasPossibleLoss = missing > 0 || quality == "SESSION_LOSS",
            terminationReason = termination,
            localUserId = user.id.ifBlank { profile.id },
            userName = user.name.ifBlank { profile.name },
            userPhone = user.phone,
            localPetId = profile.id,
            petName = profile.dogName,
            animalType = profile.animalType,
            breed = profile.breed,
            sex = profile.gender,
            age = profile.age,
            weightKg = profile.weight,
            deviceId = TagSession.connectedDevice?.name ?: "Tag_PDM",
            deviceAddress = TagSession.connectedDevice?.address.orEmpty(),
            firmwareVersion = TagSession.firmwareVersion,
            startTimeMs = startMs.takeIf { it > 0L } ?: endMs,
            endTimeMs = endMs,
            videoWidth = null,
            videoHeight = null,
            videoFps = null,
            orientationHint = null,
            videoOrientationLabel = null,
            sensorSamplePeriodMs = cfg.frameMs,
            sensorSamplesPerPacket = cfg.chunkSamples,
            phoneStartTimestampMs = startMs.takeIf { it > 0L } ?: endMs,
            collarUptimeAtSyncMs = null,
            hasData = false,
            hasVideo = false,
            hasAudio = wavOk,
            galleryUri = null,
            appVersionName = pkgInfo?.versionName ?: "0.3.0",
            appVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pkgInfo?.longVersionCode?.toInt() ?: 0
            } else {
                @Suppress("DEPRECATION")
                pkgInfo?.versionCode ?: 0
            },
            qualityLabel = quality,
            missingSamplePercent = missPct,
        )
        if (sessionId.isBlank()) sessionId = manifest.sessionId
        TagSession.sessionBaseName = sessionId
        val entry = RecordingStore.saveRecording(
            context = this,
            baseName = sessionId,
            logContent = logBody,
            packetCount = packets,
            sampleCount = samples,
            status = quality,
            manifest = manifest,
        )
        (application as TagApp).indexSessionAsync(entry)
        TagSession.lastHistoryEntry = entry
        TagSession.recordingState = RecordingState.RECEIVED
        uiState = UiState.IDLE
        saving = false
        setGainEnabled(true)
        renderRecordButton()
        binding.recordDurationText.visibility = View.GONE
        releaseWakeLock()
        val lossLine = if (missPct > 0) getString(R.string.mic_missing_pct, missPct) else ""
        val msg = if (quality == "SESSION_LOSS") {
            getString(R.string.after_stop_mic_loss, entry.baseName, wavSize) + lossLine
        } else {
            getString(R.string.after_stop_mic_saved, entry.baseName, quality, wavSize) + lossLine
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.after_stop_title)
            .setMessage(msg)
            .setPositiveButton(R.string.history) { _, _ ->
                startActivity(Intent(this, HistoryActivity::class.java))
                if (finishAfterSave) finish()
            }
            .setNegativeButton(android.R.string.ok) { _, _ ->
                if (finishAfterSave) finish()
            }
        if (finishAfterSave) builder.setOnCancelListener { finish() }
        builder.show()
        Toast.makeText(this, R.string.after_stop_mic_toast, Toast.LENGTH_SHORT).show()
        TagLogger.log(LogCategory.FILE, "MIC_SAVED", "$sessionId wav=$wavSize q=$quality")
    }

    private fun bindGainUi() {
        binding.gainSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val g = gain ?: return
                val db = (g.minDb + progress).coerceIn(g.minDb, g.maxDb).coerceIn(ABS_MIN, ABS_MAX)
                binding.gainCurrentText.text = getString(R.string.mic_gain_db, db)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seekingGain = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekingGain = false
                if (uiState != UiState.IDLE) return
                val g = gain ?: return
                val db = (g.minDb + seekBar.progress).coerceIn(g.minDb, g.maxDb).coerceIn(ABS_MIN, ABS_MAX)
                bleManager.sendAscii(Ble2Command.gainSet(db))
            }
        })
        binding.gainMinMinus.setOnClickListener { nudgeLimit(-1, Limit.MIN) }
        binding.gainMinPlus.setOnClickListener { nudgeLimit(1, Limit.MIN) }
        binding.gainMaxMinus.setOnClickListener { nudgeLimit(-1, Limit.MAX) }
        binding.gainMaxPlus.setOnClickListener { nudgeLimit(1, Limit.MAX) }
        binding.gainDefMinus.setOnClickListener { nudgeLimit(-1, Limit.DEF) }
        binding.gainDefPlus.setOnClickListener { nudgeLimit(1, Limit.DEF) }
    }

    private enum class Limit { MIN, MAX, DEF }

    private fun nudgeLimit(delta: Int, which: Limit) {
        if (uiState != UiState.IDLE) return
        val g = gain ?: return
        when (which) {
            Limit.MIN -> {
                val v = (g.minDb + delta).coerceIn(ABS_MIN, minOf(ABS_MAX, g.maxDb - 1))
                bleManager.sendAscii(Ble2Command.gainMinSet(v))
            }
            Limit.MAX -> {
                val v = (g.maxDb + delta).coerceIn(maxOf(ABS_MIN, g.minDb + 1), ABS_MAX)
                bleManager.sendAscii(Ble2Command.gainMaxSet(v))
            }
            Limit.DEF -> {
                val v = (g.defaultDb + delta).coerceIn(g.minDb, g.maxDb).coerceIn(ABS_MIN, ABS_MAX)
                bleManager.sendAscii(Ble2Command.gainDefaultSet(v))
            }
        }
    }

    private fun refreshGainLabels() {
        val g = gain
        if (g == null) {
            binding.gainCurrentText.text = "—"
            binding.gainMinText.text = "—"
            binding.gainMaxText.text = "—"
            binding.gainDefText.text = "—"
            return
        }
        if (!seekingGain) {
            val span = (g.maxDb - g.minDb).coerceAtLeast(1)
            binding.gainSeek.max = span
            binding.gainSeek.progress = (g.currentDb - g.minDb).coerceIn(0, span)
        }
        binding.gainCurrentText.text = getString(R.string.mic_gain_db, g.currentDb)
        binding.gainMinText.text = getString(R.string.mic_gain_db, g.minDb)
        binding.gainMaxText.text = getString(R.string.mic_gain_db, g.maxDb)
        binding.gainDefText.text = getString(R.string.mic_gain_db, g.defaultDb)
    }

    private fun setGainEnabled(enabled: Boolean) {
        binding.gainSeek.isEnabled = enabled && gain != null
        binding.gainMinMinus.isEnabled = enabled && gain != null
        binding.gainMinPlus.isEnabled = enabled && gain != null
        binding.gainMaxMinus.isEnabled = enabled && gain != null
        binding.gainMaxPlus.isEnabled = enabled && gain != null
        binding.gainDefMinus.isEnabled = enabled && gain != null
        binding.gainDefPlus.isEnabled = enabled && gain != null
        binding.gainScroll.alpha = if (enabled) 1f else 0.45f
    }

    private fun renderRecordButton() {
        val recording = uiState == UiState.RECORDING || uiState == UiState.STOPPING
        binding.recordBtnInner.setBackgroundResource(
            if (recording) R.drawable.bg_record_btn_inner_active else R.drawable.bg_record_btn_inner,
        )
        binding.recordBtnLabel.setText(if (recording) R.string.stop else R.string.start)
        binding.recordBtnContainer.isEnabled = uiState == UiState.IDLE || uiState == UiState.RECORDING
        binding.recordBtnContainer.alpha = if (binding.recordBtnContainer.isEnabled) 1f else 0.5f
    }

    private fun renderConnection() {
        val connected = bleManager.isTagReady
        val name = TagSession.connectedDevice?.name ?: "Tag_PDM"
        if (uiState == UiState.IDLE) {
            binding.statusBanner.text = if (connected) {
                getString(R.string.mic_connected, name)
            } else {
                getString(R.string.disconnected)
            }
        }
    }

    private fun micStorageOk(): Boolean {
        val free = try {
            val s = StatFs(filesDir.absolutePath)
            s.availableBlocksLong * s.blockSizeLong
        } catch (_: Exception) {
            return true
        }
        if (free < MIC_MIN_FREE) {
            Toast.makeText(
                this,
                getString(R.string.mic_storage_low, StorageGate.formatBytes(free)),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        return true
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tag:MicLive").apply {
            setReferenceCounted(false)
            acquire(60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private enum class UiState { IDLE, STARTING, RECORDING, STOPPING, SAVING }

    companion object {
        private const val ABS_MIN = -20
        private const val ABS_MAX = 20
        private const val CMD_TIMEOUT_MS = 3_000L
        private const val STREAM_STALL_MS = 3_000L
        private const val MIC_MIN_FREE = 50L * 1024L * 1024L
    }
}
