package com.marc.parallax3d.player

import org.json.JSONObject

/** P3D1 sidecar/comment metadata. Defaults cover files with metadata stripped. */
data class PackedMetadata(
    val convergence: Float = 0.5f,
    val eyeWidth: Int = 1920,
    val eyeHeight: Int = 1080,
) {
    val eyeAspect: Float get() = eyeWidth.toFloat() / eyeHeight

    companion object {
        /** Parse transcoder JSON (mp4 comment or .p3d.json). Null when not P3D1. */
        fun fromJson(text: String?): PackedMetadata? {
            if (text.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(text)
                if (obj.optString("format") != "P3D1") return null
                PackedMetadata(
                    convergence = obj.optDouble("convergence", 0.5).toFloat(),
                    eyeWidth = obj.optInt("eye_width", 1920),
                    eyeHeight = obj.optInt("eye_height", 1080),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
