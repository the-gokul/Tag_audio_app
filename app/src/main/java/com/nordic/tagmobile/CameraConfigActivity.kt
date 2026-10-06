package com.nordic.tagmobile

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityCameraConfigBinding
import com.nordic.tagmobile.model.CameraConfig

/**
 * Camera configuration screen.
 * Allows user to set: Resolution, Orientation, Video Format, Video Codec, Frame Rate.
 */
class CameraConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraConfigBinding
    private lateinit var currentConfig: CameraConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Camera Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        currentConfig = CameraConfig.load(this)
        setupSpinners()

        binding.saveCameraConfigBtn.setOnClickListener { saveConfig() }
        UiFeatureConfig.visible(
            binding.saveCameraConfigBtn,
            UiFeatureConfig.SHOW_SAVE_CAMERA_CONFIG_BUTTON,
        )
    }

    private fun setupSpinners() {
        // Resolution
        val resLabels = CameraConfig.Resolution.entries.map { it.label }
        binding.resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resLabels)
        binding.resolutionSpinner.setSelection(currentConfig.resolution.ordinal)

        // Orientation
        val oriLabels = CameraConfig.Orientation.entries.map { it.label }
        binding.orientationSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, oriLabels)
        binding.orientationSpinner.setSelection(currentConfig.orientation.ordinal)

        // Video Format
        val fmtLabels = CameraConfig.VideoFormat.entries.map { it.label }
        binding.videoFormatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fmtLabels)
        binding.videoFormatSpinner.setSelection(currentConfig.videoFormat.ordinal)

        // Video Codec
        val codecLabels = CameraConfig.VideoCodec.entries.map { it.label }
        binding.videoCodecSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecLabels)
        binding.videoCodecSpinner.setSelection(currentConfig.videoCodec.ordinal)

        // Frame Rate
        val fpsLabels = CameraConfig.FrameRate.entries.map { it.label }
        binding.frameRateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsLabels)
        binding.frameRateSpinner.setSelection(currentConfig.frameRate.ordinal)
    }

    private fun saveConfig() {
        val config = CameraConfig(
            resolution = CameraConfig.Resolution.entries[binding.resolutionSpinner.selectedItemPosition],
            orientation = CameraConfig.Orientation.entries[binding.orientationSpinner.selectedItemPosition],
            videoFormat = CameraConfig.VideoFormat.entries[binding.videoFormatSpinner.selectedItemPosition],
            videoCodec = CameraConfig.VideoCodec.entries[binding.videoCodecSpinner.selectedItemPosition],
            frameRate = CameraConfig.FrameRate.entries[binding.frameRateSpinner.selectedItemPosition],
        )
        CameraConfig.save(this, config)
        TagSession.cameraConfig = config
        Toast.makeText(this, "Camera settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
