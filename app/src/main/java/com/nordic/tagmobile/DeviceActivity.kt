package com.nordic.tagmobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.util.Log
import android.view.MenuItem
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nordic.tagmobile.analysis.SessionAnalyzer
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.camera.LiveTimestampComposer
import com.nordic.tagmobile.databinding.ActivityDeviceBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.protocol.SensorPacketParser
import com.nordic.tagmobile.protocol.SensorPacketParser.HEADER_SIZE
import com.nordic.tagmobile.protocol.XlsxExporter
import com.nordic.tagmobile.storage.GalleryPublisher
import com.nordic.tagmobile.storage.RecordingStore
import com.nordic.tagmobile.storage.SessionManifestData
import com.nordic.tagmobile.storage.StorageGate
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBinding
    private val bleManager get() = TagApp.instance.bleManager

    // Camera fields
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var liveTimestampComposer: LiveTimestampComposer? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var videoFile: File? = null
    private var isRecording = false
    private var isFlashOn = false
    private var isFrontCamera = false
    private var timestampHandler: Handler? = null
    private val timestampRunnable = object : Runnable {
        override fun run() {
            updateTimestamp()
            timestampHandler?.postDelayed(this, 500)
        }
    }
    private var recordStartedAtElapsed = 0L
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val totalSec = ((SystemClock.elapsedRealtime() - recordStartedAtElapsed) / 1000L).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            binding.recordDurationText.text = String.format(Locale.US, "%02d:%02d", mm, ss)
            timestampHandler?.postDelayed(this, 200)
        }
    }
    private var previewSize: Size? = null
    private var activeCameraId: String? = null

    private var orientationEventListener: OrientationEventListener? = null
    private var currentPhysicalRotation = Surface.ROTATION_0
    private var recordWakeLock: PowerManager.WakeLock? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            configureTransform(w, h)
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); cameraDevice = null
            runOnUiThread { Toast.makeText(this@DeviceActivity, "Camera error $error", Toast.LENGTH_SHORT).show() }
        }
    }

    private val bleListener = object : TagBleManager.Listener {
        override fun onReady(device: android.bluetooth.BluetoothDevice) = Unit

        override fun onDisconnected() {
            runOnUiThread {
                TagLogger.log(LogCategory.BLE, "DISCONNECTED", deviceLabel())
                val midRecord = isRecording ||
                    TagSession.recordingState == RecordingState.RECEIVING
                if (midRecord) {
                    // Option B: no reconnect mid-record — show banner then auto-save SESSION_LOSS.
                    binding.bleLossBanner.visibility = View.VISIBLE
                    if (!isRecording) {
                        // Race: RECEIVING set before UI flag — still allow stop+save.
                        isRecording = true
                    }
                    stopRecording(
                        terminationReason = "BLE_DISCONNECT",
                        qualityOverride = "SESSION_LOSS",
                        skipBleStop = true,
                        finishAfterSave = true,
                    )
                } else {
                    TagSession.clearConnection()
                    Toast.makeText(this@DeviceActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        override fun onPacket(data: ByteArray) {
            if (TagSession.recordingState != RecordingState.RECEIVING) return
            if (TagSession.tagUptimeAtSync == null && data.size >= HEADER_SIZE) {
                val buf = java.nio.ByteBuffer.wrap(data)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.position(14)
                TagSession.tagUptimeAtSync = buf.int.toLong() and 0xFFFFFFFFL
            }
            val parsed = SensorPacketParser.parsePacket(
                data,
                TagSession.syncBaseUnixMs,
                TagSession.tagUptimeAtSync,
            )
            if (parsed == null) {
                TagSession.parseFailures++
                TagLogger.log(LogCategory.ERRORS, "PARSE_FAIL", "bytes=${data.size}")
                return
            }
            runOnUiThread {
                maybeUpdateDeviceId(parsed.deviceId)
                TagSession.receivedRows.addAll(parsed.rows)
                TagSession.packetIds.add(parsed.packetId)
                TagSession.packetCount++
                TagLogger.logDataVerbose(
                    "PACKET",
                    "id=${parsed.packetId} samples=${parsed.rows.size}",
                )
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                TagLogger.log(LogCategory.ERRORS, "BLE_ERROR", message)
                if (isRecording && message.contains("START", ignoreCase = true)) {
                    // Tag rejected Start after camera already rolled — stop video and reset UI
                    try {
                        captureSession?.stopRepeating()
                        mediaRecorder?.stop()
                    } catch (_: Exception) {
                    }
                    releaseLiveTimestampComposer()
                    try {
                        mediaRecorder?.release()
                    } catch (_: Exception) {
                    }
                    mediaRecorder = null
                    discardIncompleteSession()
                    isRecording = false
                    TagSession.recordingState = RecordingState.IDLE
                    setRecordButtonUi(recording = false)
                    startPreview()
                    Toast.makeText(this@DeviceActivity, message, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@DeviceActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val device = TagSession.connectedDevice
        if (device == null) {
            finish()
            return
        }

        // Force portrait UI + portrait encode (no landscape recording path)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        binding.deviceTitle.text = device.name
        val petName = TagSession.userProfile.dogName.ifBlank { getString(R.string.home_pet_none) }
        binding.petSubtitle.text = getString(R.string.record_pet_subtitle, petName)
        binding.backBtn.setOnClickListener { finish() }
        binding.deviceMenuBtn.setOnClickListener { showDeviceMenu(it) }
        
        binding.recordBtnContainer.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }
        binding.flashBtn.setOnClickListener { toggleFlash() }
        binding.switchCameraBtn.setOnClickListener { switchCamera() }
        binding.recordingsBtn.setOnClickListener {
            if (isRecording) return@setOnClickListener
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        applyUiFeatureConfig()
        setRecordButtonUi(recording = false)

        bleManager.listener = bleListener
        
        binding.timestampText.text = currentTimestamp()

        timestampHandler = Handler(mainLooper)
        timestampHandler?.post(timestampRunnable)

        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Map the 0-359 physical angle to matching Surface.ROTATION constants
                // 0 is portrait, 90 is right side down (landscape), etc.
                val newRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270 // Right edge down
                    in 135..224 -> Surface.ROTATION_180 // Top edge down
                    in 225..314 -> Surface.ROTATION_90 // Left edge down
                    else -> Surface.ROTATION_0 // Portrait
                }
                if (newRotation != currentPhysicalRotation) {
                    currentPhysicalRotation = newRotation
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener?.enable()
        // While recording, leave camera/recorder alone — reopening would stop the take.
        if (isRecording) {
            setKeepScreenOn(true)
            return
        }
        startBackgroundThread()
        if (binding.cameraPreview.isAvailable) {
            openCamera()
        } else {
            binding.cameraPreview.surfaceTextureListener = surfaceListener
        }
    }

    override fun onPause() {
        orientationEventListener?.disable()
        // Do not tear down camera mid-recording (screen-off / brief focus loss).
        if (!isRecording) {
            closeCamera()
            stopBackgroundThread()
        }
        super.onPause()
    }

    override fun onDestroy() {
        timestampHandler?.removeCallbacks(timestampRunnable)
        timestampHandler?.removeCallbacks(durationRunnable)
        releaseRecordWakeLock()
        if (bleManager.listener === bleListener) {
            bleManager.listener = null
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            facing == if (isFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        activeCameraId = cameraId
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val choices = map?.getOutputSizes(SurfaceTexture::class.java)
        previewSize = choosePreviewSize(choices)
        previewSize?.let { size ->
            binding.cameraPreview.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
            configureTransform(binding.cameraPreview.width, binding.cameraPreview.height)
        }
        manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    /** Prefer sensor aspect vs view aspect (phone-camera style). */
    private fun choosePreviewSize(choices: Array<Size>?): Size {
        if (choices.isNullOrEmpty()) return Size(1280, 720)
        val viewW = binding.cameraPreview.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val viewH = binding.cameraPreview.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val viewAspect = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)
        val candidates = choices.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { choices.toList() }
        return candidates.minByOrNull { size ->
            val sensorAspect = size.width.toFloat() / size.height.toFloat()
            val previewAspect = if (isPortraitDisplay()) 1f / sensorAspect else sensorAspect
            kotlin.math.abs(previewAspect - viewAspect)
        } ?: candidates[0]
    }

    private fun isPortraitDisplay(): Boolean {
        val rot = windowManager.defaultDisplay.rotation
        return rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180
    }

    /**
     * Center-crop preview (phone camera style) — fills the frame without squashing.
     * Based on Camera2Basic transform, applied for all rotations.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val size = previewSize ?: return
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(
            viewHeight.toFloat() / bufferRect.height(),
            viewWidth.toFloat() / bufferRect.width(),
        )
        matrix.postScale(scale, scale, centerX, centerY)
        when (rotation) {
            Surface.ROTATION_90 -> matrix.postRotate(90f, centerX, centerY)
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
            Surface.ROTATION_270 -> matrix.postRotate(270f, centerX, centerY)
        }
        binding.cameraPreview.setTransform(matrix)
    }

    /**
     * MediaRecorder orientation hint (clockwise). Gallery uses this so a
     * landscape-encoded file plays as portrait when the phone was held upright.
     */
    private fun videoOrientationHint(): Int {
        val cameraId = activeCameraId ?: return 90
        return try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            
            // Use physical rotation tracked by our listener instead of locked display rotation
            val deviceRotation = when (currentPhysicalRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + deviceRotation) % 360
            } else {
                (sensorOrientation - deviceRotation + 360) % 360
            }
        } catch (_: Exception) {
            90
        }
    }

    /** Prevent screen timeout from pausing/closing the recording UI. */
    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            acquireRecordWakeLock()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            releaseRecordWakeLock()
        }
        binding.root.keepScreenOn = enabled
    }

    private fun acquireRecordWakeLock() {
        if (recordWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        recordWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tag:Recording").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseRecordWakeLock() {
        try {
            if (recordWakeLock?.isHeld == true) recordWakeLock?.release()
        } catch (_: Exception) {
        }
        recordWakeLock = null
    }

    private fun setRecordButtonUi(recording: Boolean) {
        setKeepScreenOn(recording)
        val density = resources.displayMetrics.density
        val sizeDp = if (recording) 28f else 60f
        val px = (sizeDp * density).toInt()
        val lp = binding.recordBtnInner.layoutParams
        lp.width = px
        lp.height = px
        binding.recordBtnInner.layoutParams = lp
        binding.recordBtnInner.setBackgroundResource(
            if (recording) R.drawable.bg_record_btn_inner_active
            else R.drawable.bg_record_btn_inner,
        )
        binding.recordBtnLabel.text = getString(if (recording) R.string.stop else R.string.start)
        if (recording) {
            recordStartedAtElapsed = SystemClock.elapsedRealtime()
            binding.recordDurationText.text = "00:00"
            binding.recordDurationText.visibility = View.VISIBLE
            timestampHandler?.removeCallbacks(durationRunnable)
            timestampHandler?.post(durationRunnable)
        } else {
            timestampHandler?.removeCallbacks(durationRunnable)
            binding.recordDurationText.visibility = View.GONE
            binding.recordDurationText.text = "00:00"
        }
    }

    private fun toggleFlash() {
        if (isFrontCamera) return
        isFlashOn = !isFlashOn
        startPreview()
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        isFlashOn = false
        closeCamera()
        openCamera()
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        releaseLiveTimestampComposer()
        mediaRecorder?.release(); mediaRecorder = null
    }

    private fun startPreview() {
        val texture = binding.cameraPreview.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.FLASH_MODE, if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }
        cameraDevice!!.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@DeviceActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler,
        )
    }

    private fun deviceLabel(): String =
        TagSession.connectedDevice?.let { "${it.name} ${it.address}" } ?: "?"

    private fun maybeUpdateDeviceId(deviceId: String) {
        val connected = TagSession.connectedDevice ?: return
        if (connected.name == deviceId) return
        if (connected.name.equals("Tag", ignoreCase = true) ||
            !connected.name.startsWith("Tag_", ignoreCase = true)
        ) {
            connected.name = deviceId
            binding.deviceTitle.text = deviceId
        }
    }

    private fun applyUiFeatureConfig() {
        UiFeatureConfig.visible(binding.recordBtnGroup, UiFeatureConfig.SHOW_CAMERA_RECORD_BUTTON)
        UiFeatureConfig.visible(binding.flashBtn, UiFeatureConfig.SHOW_FLASH_BUTTON)
        UiFeatureConfig.visible(binding.switchCameraBtn, UiFeatureConfig.SHOW_SWITCH_CAMERA_BUTTON)
        UiFeatureConfig.visible(binding.recordingsBtn, UiFeatureConfig.SHOW_DEVICE_RECORDINGS_BUTTON)
        UiFeatureConfig.visible(binding.deviceMenuBtn, UiFeatureConfig.SHOW_DEVICE_MENU_BUTTON)
    }

    private fun showDeviceMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        if (UiFeatureConfig.SHOW_DEVICE_MENU_DISCONNECT) {
            popup.menu.add(0, 1, 0, getString(R.string.disconnect))
        }
        if (UiFeatureConfig.SHOW_DEVICE_MENU_LOGS) {
            popup.menu.add(0, 2, 0, getString(R.string.logs))
        }
        if (UiFeatureConfig.SHOW_DEVICE_MENU_HISTORY) {
            popup.menu.add(0, 3, 0, getString(R.string.history))
        }
        if (UiFeatureConfig.SHOW_DEVICE_MENU_PROFILE) {
            popup.menu.add(0, 4, 0, getString(R.string.profile))
        }
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> {
                    bleManager.disconnectTag()
                    TagSession.clearConnection()
                    finish()
                    true
                }
                2 -> {
                    startActivity(Intent(this, LogViewerActivity::class.java))
                    true
                }
                3 -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                4 -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun startRecording() {
        if (!UiFeatureConfig.SHOW_CAMERA_RECORD_BUTTON) return
        if (!bleManager.isTagReady) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val camera = cameraDevice
        val texture = binding.cameraPreview.surfaceTexture
        if (camera == null || texture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val storage = StorageGate.check(this)
        if (!storage.ok) {
            TagLogger.log(
                LogCategory.ERRORS,
                "STORAGE_LOW",
                "free=${storage.freeBytes}",
            )
            Toast.makeText(this, storage.message, Toast.LENGTH_LONG).show()
            return
        }

        // Session folder can be named at tap; sync clock waits until video actually rolls (Option A).
        TagLogger.clearSessionLog()
        TagSession.receivedRows.clear()
        TagSession.packetIds.clear()
        TagSession.packetCount = 0
        TagSession.parseFailures = 0
        TagSession.lastFeedbackText = ""
        TagSession.lastHistoryEntry = null
        TagSession.tagUptimeAtSync = null
        TagSession.syncBaseUnixMs = 0L
        TagSession.recordingStartUnixMs = 0L
        val folderTimeMs = System.currentTimeMillis()
        TagSession.sessionBaseName = RecordingStore.makeSessionId(folderTimeMs)

        val sessionDir = RecordingStore.createSessionDir(this, TagSession.sessionBaseName)
        videoFile = RecordingStore.sessionVideoFile(sessionDir)

        // Phone-default camcorder profile (no CameraConfig forced size/orientation)
        val camProfile = try {
            val id = activeCameraId?.toIntOrNull()
            when {
                id != null && CamcorderProfile.hasProfile(id, CamcorderProfile.QUALITY_720P) ->
                    CamcorderProfile.get(id, CamcorderProfile.QUALITY_720P)
                id != null && CamcorderProfile.hasProfile(id, CamcorderProfile.QUALITY_HIGH) ->
                    CamcorderProfile.get(id, CamcorderProfile.QUALITY_HIGH)
                CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P) ->
                    CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
                else -> CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
            }
        } catch (_: Exception) {
            CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
        }

        // Standard Android camera record: landscape pixels + orientation hint for portrait play.
        // (Many devices reject / mishandle HxW portrait encode sizes.)
        val outW = camProfile.videoFrameWidth
        val outH = camProfile.videoFrameHeight
        val orientationHint = videoOrientationHint()
        TagSession.recordingVideoWidth = outW
        TagSession.recordingVideoHeight = outH
        TagSession.recordingVideoFps = camProfile.videoFrameRate
        TagSession.recordingOrientationHint = orientationHint

        val cameraId = activeCameraId ?: "0"
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val sensorOrientation = try {
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Exception) { 90 }

        Log.d("OrientationDebug", "Physical orientation = $currentPhysicalRotation (0=Port, 1=Land, 2=RevPort, 3=RevLand)")
        Log.d("OrientationDebug", "Sensor orientation = $sensorOrientation")
        Log.d("OrientationDebug", "Recording orientation (hint) = $orientationHint")
        Log.d("OrientationDebug", "Video size = ${outW}x${outH}")
        Log.d("OrientationDebug", "Timestamp orientation = $orientationHint")

        TagLogger.log(
            LogCategory.FILE,
            "VIDEO_ENCODE",
            "size=${outW}x$outH orientationHint=$orientationHint",
        )
        val mr: MediaRecorder
        try {
            @Suppress("DEPRECATION")
            mr = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(outW, outH)
                setVideoFrameRate(camProfile.videoFrameRate)
                setVideoEncodingBitRate(camProfile.videoBitRate)
                setOrientationHint(orientationHint)
                setOutputFile(videoFile!!.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "VIDEO_PREPARE_ERR", e.message ?: "")
            discardIncompleteSession()
            Toast.makeText(this, "Video setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        mediaRecorder = mr

        // Camera → GL (timestamp compensated for orientationHint) → MediaRecorder
        val burnSurface: Surface
        try {
            releaseLiveTimestampComposer()
            val composer = LiveTimestampComposer(
                outputSurface = mr.surface,
                videoWidth = outW,
                videoHeight = outH,
                orientationHint = orientationHint,
                sensorOrientation = sensorOrientation,
                timestampText = { currentTimestamp() },
            )
            composer.start()
            burnSurface = composer.cameraInputSurface
                ?: throw IllegalStateException("Live timestamp input surface missing")
            liveTimestampComposer = composer
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "LIVE_TIMESTAMP_ERR", e.message ?: "")
            abortStartAfterCameraFail("Live timestamp failed: ${e.message}")
            return
        }

        val previewSurface = Surface(texture)
        val request = try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(burnSurface)
                set(
                    CaptureRequest.FLASH_MODE,
                    if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                )
            }
        } catch (e: Exception) {
            abortStartAfterCameraFail("Capture request failed: ${e.message}")
            return
        }

        captureSession?.close()
        try {
            camera.createCaptureSession(
                listOf(previewSurface, burnSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request.build(), null, backgroundHandler)
                            mr.start()
                        } catch (e: Exception) {
                            runOnUiThread {
                                abortStartAfterCameraFail("Video start failed: ${e.message}")
                            }
                            return
                        }

                        // Option A: shared session zero = video start (not Start-tap).
                        // Excel labels + BLE START use this clock so they align with burned video time.
                        val videoStartUnixMs = System.currentTimeMillis()
                        TagSession.syncBaseUnixMs = videoStartUnixMs
                        TagSession.recordingStartUnixMs = videoStartUnixMs
                        TagSession.tagUptimeAtSync = null
                        TagSession.recordingState = RecordingState.RECEIVING
                        TagLogger.log(
                            LogCategory.CONTROL,
                            "START",
                            "unix_ms=$videoStartUnixMs base=${TagSession.sessionBaseName} device=${deviceLabel()} sync=video_start",
                        )
                        bleManager.startRecording(videoStartUnixMs)
                        TagLogger.log(LogCategory.FILE, "VIDEO_RECORDING_START", videoFile!!.name)

                        runOnUiThread {
                            isRecording = true
                            setRecordButtonUi(recording = true)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread {
                            abortStartAfterCameraFail("Recording setup failed")
                        }
                    }
                },
                backgroundHandler,
            )
        } catch (e: Exception) {
            abortStartAfterCameraFail("Camera session failed: ${e.message}")
        }
    }

    private fun releaseLiveTimestampComposer() {
        try {
            liveTimestampComposer?.release()
        } catch (_: Exception) {
        }
        liveTimestampComposer = null
    }

    /** Release recorder / partial video file when Start fails before BLE is running. */
    private fun abortStartAfterCameraFail(message: String) {
        TagLogger.log(LogCategory.ERRORS, "VIDEO_START_ABORT", message)
        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }
        releaseLiveTimestampComposer()
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        discardIncompleteSession()
        TagSession.recordingState = RecordingState.IDLE
        isRecording = false
        setRecordButtonUi(recording = false)
        startPreview()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Delete partial session folder / video when Start fails before a successful save. */
    private fun discardIncompleteSession() {
        val sessionId = TagSession.sessionBaseName
        videoFile?.delete()
        videoFile = null
        if (sessionId.startsWith("SESSION-")) {
            val dir = RecordingStore.sessionDir(this, sessionId)
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
        TagSession.sessionBaseName = ""
    }

    private fun stopRecording(
        terminationReason: String = "USER_STOP",
        qualityOverride: String? = null,
        skipBleStop: Boolean = false,
        finishAfterSave: Boolean = false,
    ) {
        if (!isRecording) return
        isRecording = false
        setRecordButtonUi(recording = false)

        // Snapshot before any clearConnection path
        val deviceNameSnap = TagSession.connectedDevice?.name
            ?: TagSession.receivedRows.firstOrNull()?.deviceId
            ?: "Tag"
        val deviceAddrSnap = TagSession.connectedDevice?.address ?: ""
        val firmwareSnap = TagSession.firmwareVersion
        val rowsSnap = TagSession.receivedRows.toList()
        val packetIdsSnap = TagSession.packetIds.toList()
        val packetCountSnap = TagSession.packetCount
        val parseFailSnap = TagSession.parseFailures
        val syncBaseSnap = TagSession.syncBaseUnixMs
        val uptimeSnap = TagSession.tagUptimeAtSync
        val startMsSnap = TagSession.recordingStartUnixMs.takeIf { it > 0L }
            ?: syncBaseSnap.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val sessionIdSnap = TagSession.sessionBaseName
        val profileSnap = TagSession.userProfile
        val userSnap = TagSession.appUser
        val deviceConfigSnap = TagSession.deviceConfig
        val videoW = TagSession.recordingVideoWidth
        val videoH = TagSession.recordingVideoHeight
        val videoFps = TagSession.recordingVideoFps
        val orientHint = TagSession.recordingOrientationHint

        if (!skipBleStop) {
            bleManager.stopRecording()
            TagLogger.log(LogCategory.CONTROL, "STOP", deviceLabel())
        } else {
            TagLogger.log(LogCategory.CONTROL, "STOP_BLE_DISCONNECT", deviceLabel())
        }

        // Stop video (timestamp already burned live into frames)
        try {
            captureSession?.stopRepeating()
            mediaRecorder?.stop()
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "VIDEO_STOP_ERR", e.message ?: "")
        }
        releaseLiveTimestampComposer()
        mediaRecorder?.release(); mediaRecorder = null
        TagLogger.log(LogCategory.FILE, "VIDEO_SAVED", videoFile?.name ?: "")

        if (!finishAfterSave) {
            startPreview()
        }

        val vFile = videoFile
        val vSize = vFile?.length()?.let { formatBytes(it) } ?: "?"
        val endMs = System.currentTimeMillis()
        val startMs = startMsSnap
        val durationSec = ((endMs - startMs).coerceAtLeast(0L) / 1000L)
        val hasVideo = vFile != null && vFile.exists() && vFile.length() > 0L

        val baseReport = SessionAnalyzer.analyze(
            packetCount = packetCountSnap,
            rows = rowsSnap,
            packetIds = packetIdsSnap,
            parseFailures = parseFailSnap,
            durationSec = durationSec,
            hasVideo = hasVideo,
        )
        val report = if (qualityOverride != null) {
            val detail = listOfNotNull(
                "BLE/Tag disconnected during recording",
                baseReport.statusDetail.takeIf { it.isNotBlank() },
            ).joinToString("; ")
            baseReport.copy(
                qualityStatus = qualityOverride,
                statusShort = qualityOverride,
                statusDetail = detail,
                hasPossibleLoss = true,
            )
        } else {
            baseReport
        }

        if (report.qualityStatus != "GOOD") {
            TagLogger.log(LogCategory.GAPS, "SESSION_QUALITY", "${report.qualityStatus} ${report.statusDetail}")
        } else {
            TagLogger.logDataSummary(
                "SESSION_OK",
                "packets=${report.packetCount} samples=${report.sampleCount}",
            )
        }
        TagLogger.logDataSummary(
            "SESSION_SUMMARY",
            "packets=${report.packetCount} samples=${report.sampleCount} quality=${report.qualityStatus} term=$terminationReason",
        )

        TagSession.lastFeedbackText = report.feedbackText
        TagSession.recordingState = RecordingState.SAVING

        val recordedVideo = vFile

        try {
            val sessionId = sessionIdSnap.ifBlank {
                RecordingStore.makeSessionId(syncBaseSnap.takeIf { it > 0L } ?: System.currentTimeMillis())
            }
            TagSession.sessionBaseName = sessionId
            val sessionDir = RecordingStore.createSessionDir(this, sessionId)
            val dataFile = RecordingStore.sessionDataFile(sessionDir)
            XlsxExporter.write(
                outFile = dataFile,
                rows = rowsSnap,
                summary = XlsxExporter.SummaryInfo(
                    profile = profileSnap,
                    deviceConfig = deviceConfigSnap,
                    deviceName = deviceNameSnap,
                    packetCount = report.packetCount,
                    sampleCount = report.sampleCount,
                    status = report.qualityStatus,
                ),
            )

            val logBody = buildString {
                appendLine("Tag session log")
                appendLine("session_id=$sessionId")
                appendLine("device=$deviceNameSnap $deviceAddrSnap")
                appendLine("termination=$terminationReason")
                appendLine("packets=${report.packetCount}")
                appendLine("samples=${report.sampleCount}")
                appendLine("quality=${report.qualityStatus}")
                appendLine("status=${report.statusShort}")
                if (report.statusDetail.isNotBlank()) appendLine(report.statusDetail)
                appendLine("---")
                append(TagLogger.sessionSnapshot())
            }

            val pkgInfo = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (_: Exception) {
                null
            }
            val manifest = SessionManifestData(
                sessionId = sessionId,
                packetCount = report.packetCount,
                sampleCount = report.sampleCount,
                parseFailures = report.parseFailures,
                statusDetail = report.statusDetail.ifBlank { report.statusShort },
                hasPossibleLoss = report.hasPossibleLoss,
                terminationReason = terminationReason,
                localUserId = userSnap.id.ifBlank { profileSnap.id },
                userName = userSnap.name.ifBlank { profileSnap.name },
                userPhone = userSnap.phone,
                localPetId = profileSnap.id,
                petName = profileSnap.dogName,
                animalType = profileSnap.animalType,
                breed = profileSnap.breed,
                sex = profileSnap.gender,
                age = profileSnap.age,
                weightKg = profileSnap.weight,
                deviceId = deviceNameSnap,
                deviceAddress = deviceAddrSnap,
                hardwareVersion = null,
                firmwareVersion = firmwareSnap,
                startTimeMs = startMs,
                endTimeMs = endMs,
                videoWidth = videoW,
                videoHeight = videoH,
                videoFps = videoFps,
                orientationHint = orientHint,
                videoOrientationLabel = SessionManifestData.orientationLabel(orientHint),
                sensorSamplePeriodMs = deviceConfigSnap.samplePeriodMs,
                sensorSamplesPerPacket = deviceConfigSnap.samplesPerPacket,
                phoneStartTimestampMs = syncBaseSnap.takeIf { it > 0L } ?: startMs,
                collarUptimeAtSyncMs = uptimeSnap,
                offsetMs = null,
                batteryStartPercent = null,
                batteryEndPercent = null,
                hasData = dataFile.exists(),
                hasVideo = hasVideo,
                galleryUri = null,
                appVersionName = pkgInfo?.versionName ?: "0.3.0",
                appVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pkgInfo?.longVersionCode?.toInt() ?: 0
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo?.versionCode ?: 0
                },
                qualityLabel = report.qualityStatus,
                missingSamplePercent = report.missingSamplePercent,
            )
            val entry = RecordingStore.saveRecording(
                context = this,
                baseName = sessionId,
                logContent = logBody,
                packetCount = report.packetCount,
                sampleCount = report.sampleCount,
                status = report.qualityStatus,
                manifest = manifest,
            )
            (application as TagApp).indexSessionAsync(entry)
            TagSession.lastHistoryEntry = entry
            TagSession.lastFeedbackText = report.feedbackText
            TagSession.recordingState = RecordingState.RECEIVED
            val statusLine = if (qualityOverride == "SESSION_LOSS") {
                getString(R.string.after_stop_loss, entry.baseName, vSize)
            } else {
                getString(R.string.after_stop_saved, entry.baseName, report.qualityStatus, vSize)
            }
            // UI-only feedback — save + cloud enqueue already done above.
            binding.bleLossBanner.visibility = View.GONE
            val builder = AlertDialog.Builder(this)
                .setTitle(R.string.after_stop_title)
                .setMessage(statusLine)
                .setPositiveButton(R.string.history) { _, _ ->
                    startActivity(Intent(this, HistoryActivity::class.java))
                    if (finishAfterSave) finish()
                }
                .setNegativeButton(android.R.string.ok) { _, _ ->
                    if (finishAfterSave) finish()
                }
            if (finishAfterSave) {
                TagSession.clearConnection()
                builder.setOnCancelListener { finish() }
            }
            builder.show()
            Toast.makeText(this, R.string.after_stop_toast, Toast.LENGTH_SHORT).show()

            if (recordedVideo != null && recordedVideo.exists()) {
                Thread {
                    val galleryUri = GalleryPublisher.publishVideo(
                        applicationContext,
                        recordedVideo,
                        displayName = recordedVideo.name,
                    )
                    if (galleryUri != null) {
                        RecordingStore.updateGalleryUri(
                            applicationContext,
                            sessionId,
                            galleryUri.toString(),
                        )
                        TagSession.lastHistoryEntry =
                            TagSession.lastHistoryEntry?.copy(galleryUri = galleryUri.toString())
                    }
                }.start()
            }
            // finishAfterSave: leave after dialog dismiss (above), not immediately.
            return
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "AUTO_SAVE_FAIL", e.message ?: "")
            TagSession.recordingState = RecordingState.RECEIVED
            TagSession.lastFeedbackText =
                report.feedbackText.replace(
                    "Saved to History",
                    "Save failed: ${e.message}",
                )
            Toast.makeText(this, "Auto-save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }

        if (finishAfterSave) {
            TagSession.clearConnection()
            finish()
        }
    }

    private fun currentTimestamp(): String {
        val fmt = SimpleDateFormat("EEEE, dd MMMM yyyy HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date())
    }

    private fun updateTimestamp() {
        binding.timestampText.text = currentTimestamp()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
