package com.nordic.tagmobile.model

import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaRecorder

/**
 * Camera & video recording configuration.
 * Resolution / orientation / format / codec / frame rate.
 */
data class CameraConfig(
    val resolution: Resolution = Resolution.FHD_1080P,
    val orientation: Orientation = Orientation.AUTO,
    val videoFormat: VideoFormat = VideoFormat.MP4,
    val videoCodec: VideoCodec = VideoCodec.H264_AVC,
    val frameRate: FrameRate = FrameRate.FPS_30,
) {
    enum class Resolution(val label: String, val width: Int, val height: Int) {
        HD_720P("1280 × 720 (HD)", 1280, 720),
        FHD_1080P("1920 × 1080 (FHD)", 1920, 1080),
        UHD_4K("3840 × 2160 (4K)", 3840, 2160),
    }

    enum class Orientation(val label: String, val sensorRotation: Int?) {
        AUTO("Auto (follow device)", null),
        PORTRAIT("Portrait", 90),
        LANDSCAPE("Landscape", 0),
    }

    enum class VideoFormat(val label: String, val outputFormat: Int) {
        MP4("MP4", MediaRecorder.OutputFormat.MPEG_4),
        WEBM("WebM", MediaRecorder.OutputFormat.WEBM),
    }

    enum class VideoCodec(val label: String, val encoderValue: Int) {
        H264_AVC("H.264 (AVC)", MediaRecorder.VideoEncoder.H264),
        H265_HEVC("H.265 (HEVC)", MediaRecorder.VideoEncoder.HEVC),
    }

    enum class FrameRate(val label: String, val fps: Int) {
        FPS_24("24 FPS", 24),
        FPS_30("30 FPS", 30),
        FPS_60("60 FPS", 60),
    }

    /** Pick the best CamcorderProfile for the chosen resolution. */
    fun camcorderQuality(): Int = when (resolution) {
        Resolution.UHD_4K -> CamcorderProfile.QUALITY_2160P
        Resolution.FHD_1080P -> CamcorderProfile.QUALITY_1080P
        Resolution.HD_720P -> CamcorderProfile.QUALITY_720P
    }

    companion object {
        private const val PREF_FILE = "tag_camera_config"

        fun load(context: Context): CameraConfig {
            val p = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            return CameraConfig(
                resolution = Resolution.entries.getOrElse(p.getInt("res", 1)) { Resolution.FHD_1080P },
                orientation = Orientation.entries.getOrElse(p.getInt("ori", 0)) { Orientation.AUTO },
                videoFormat = VideoFormat.entries.getOrElse(p.getInt("fmt", 0)) { VideoFormat.MP4 },
                videoCodec = VideoCodec.entries.getOrElse(p.getInt("codec", 0)) { VideoCodec.H264_AVC },
                frameRate = FrameRate.entries.getOrElse(p.getInt("fps", 1)) { FrameRate.FPS_30 },
            )
        }

        fun save(context: Context, config: CameraConfig) {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
                .putInt("res", config.resolution.ordinal)
                .putInt("ori", config.orientation.ordinal)
                .putInt("fmt", config.videoFormat.ordinal)
                .putInt("codec", config.videoCodec.ordinal)
                .putInt("fps", config.frameRate.ordinal)
                .apply()
        }
    }
}
