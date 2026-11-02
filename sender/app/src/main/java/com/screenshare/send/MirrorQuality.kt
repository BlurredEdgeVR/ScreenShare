package com.screenshare.send

/**
 * Encoder presets for screen mirroring. AS_NOW is the setup that first worked in the car,
 * so keep it unchanged and add new presets instead.
 */
enum class MirrorQuality(
    val title: String,
    val detail: String,
    /** Longest side of the encoded video, in pixels. */
    val longSide: Int,
    val fps: Int,
    val bitRate: Int,
    /** Limit frames fed to the encoder to [fps]. A 120 Hz screen can otherwise send far more. */
    val capFps: Boolean,
    /** Skip ahead to the next keyframe when the display falls behind, instead of stalling the encoder. */
    val skipWhenBehind: Boolean,
    /**
     * Always send a sideways picture shaped like the phone's screen, however the phone is held.
     * A sideways phone (Waze) fills a car screen; an upright one shows in the middle with bars.
     */
    val landscape: Boolean = false
) {
    AS_NOW("As now", "1280 px, 30 fps, 4 Mbps", 1280, 30, 4_000_000, false, false),
    SMOOTHER("Smoother", "960 px, 24 fps, 2.5 Mbps, skips ahead if the display falls behind", 960, 24, 2_500_000, true, true),
    LIGHTEST("Lightest", "720 px, 20 fps, 1.5 Mbps, skips ahead if the display falls behind", 720, 20, 1_500_000, true, true),
    LANDSCAPE("Landscape", "Turn the phone sideways to fill the car screen. 1280 px wide, 24 fps, 3 Mbps", 1280, 24, 3_000_000, true, true, true),
    LANDSCAPE_LIGHT("Landscape light", "Sideways, for a slow connection. 960 px wide, 20 fps, 1.8 Mbps", 960, 20, 1_800_000, true, true, true);

    companion object {
        fun fromName(name: String?): MirrorQuality = entries.firstOrNull { it.name == name } ?: AS_NOW
    }
}
