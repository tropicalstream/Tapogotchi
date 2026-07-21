package com.tapogotchi

import android.content.Context
import android.os.Build

/** Persistent settings + the pet itself. RayNeo hardware detected by identity, not model (guide gotcha #24). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("tapogotchi", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    private val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    init {
        if (isRayNeoX3 && !p.getBoolean("rayneoSbsV1", false)) {
            p.edit().putBoolean("sbs", true).putBoolean("rayneoSbsV1", true).apply()
        }
    }

    var soundVolume: Int
        get() = p.getInt("sndVol", 8)
        set(v) { p.edit().putInt("sndVol", v.coerceIn(0, 10)).apply() }

    var swipeSens: Float
        get() = p.getFloat("swipeSens", 1.0f)
        set(v) { p.edit().putFloat("swipeSens", v.coerceIn(0.4f, 2.5f)).apply() }

    var flipVertical: Boolean
        get() = p.getBoolean("flipV", false)
        set(v) { p.edit().putBoolean("flipV", v).apply() }

    var flipHorizontal: Boolean
        get() = p.getBoolean("flipH", false)
        set(v) { p.edit().putBoolean("flipH", v).apply() }

    var safeTap: Boolean
        get() = p.getBoolean("safeTap", true)
        set(v) { p.edit().putBoolean("safeTap", v).apply() }

    var frameCap30: Boolean
        get() = p.getBoolean("cap30", true)   // a pet idles all day; sip power
        set(v) { p.edit().putBoolean("cap30", v).apply() }

    /** Head parallax: the pet floats in your room (subtle yaw drift). */
    var parallax: Boolean
        get() = p.getBoolean("parallax", true)
        set(v) { p.edit().putBoolean("parallax", v).apply() }

    var sbs: Boolean
        get() = p.getBoolean("sbs", isRayNeoX3)
        set(v) { p.edit().putBoolean("sbs", v).apply() }

    /** Restore every preference to its default (never touches the pet). */
    fun resetSettings() {
        p.edit()
            .remove("sndVol").remove("swipeSens").remove("flipV").remove("flipH")
            .remove("safeTap").remove("cap30").remove("parallax").remove("sbs")
            .apply()
    }

    /** The whole pet, serialized after every interaction (suite convention #4). */
    var petJson: String?
        get() = p.getString("pet", null)
        set(v) { p.edit().apply { if (v == null) remove("pet") else putString("pet", v) }.apply() }

    /** Hall of ancestors: one line per completed life. */
    var lineageJson: String
        get() = p.getString("lineage", "[]") ?: "[]"
        set(v) { p.edit().putString("lineage", v).apply() }
}
