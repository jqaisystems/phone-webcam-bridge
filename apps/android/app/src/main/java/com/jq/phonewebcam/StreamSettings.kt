package com.jq.phonewebcam

enum class CameraFacing {
    FRONT,
    BACK
}

data class VideoProfile(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int
)

object VideoProfiles {
    val LOW = VideoProfile("low", "Low 360p / 15 fps", 640, 360, 15, 62)
    val HD = VideoProfile("hd", "HD 720p / 15 fps", 1280, 720, 15, 70)
    val SMOOTH = VideoProfile("smooth", "HD 720p / 30 fps", 1280, 720, 30, 68)
    val FHD = VideoProfile("fhd", "Full HD 1080p / 30 fps", 1920, 1080, 30, 65)

    val all = listOf(SMOOTH, FHD, HD, LOW)

    fun byId(id: String?): VideoProfile? = all.firstOrNull { it.id == id }
}

data class StreamSettings(
    val profile: VideoProfile = VideoProfiles.SMOOTH,
    val camera: CameraFacing = CameraFacing.FRONT,
    val mirror: Boolean = true,
    val torch: Boolean = false,
    val mic: Boolean = false
)

data class ControlCommand(
    val profileId: String? = null,
    val camera: CameraFacing? = null,
    val mirror: Boolean? = null,
    val torch: Boolean? = null,
    val zoomRatio: Float? = null,
    val exposureIndex: Int? = null,
    val mic: Boolean? = null
)

/** Live camera capabilities and values, reported in /status for the dashboard sliders. */
data class CameraLiveState(
    val zoomRatio: Float,
    val maxZoomRatio: Float,
    val exposureIndex: Int,
    val minExposureIndex: Int,
    val maxExposureIndex: Int
)
