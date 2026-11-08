package com.firdaus.rekab

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("rekab", Context.MODE_PRIVATE)

    var selectedCameras: Set<String>
        get() = sp.getStringSet("cams", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("cams", v).apply()

    var autoDetectedOnce: Boolean
        get() = sp.getBoolean("autoDetectedOnce", false)
        set(v) = sp.edit().putBoolean("autoDetectedOnce", v).apply()

    /** Max height of the recorded video: 480, 720, 1080 or 0 = largest available. */
    var maxHeight: Int
        get() = sp.getInt("maxH", 720)
        set(v) = sp.edit().putInt("maxH", v).apply()

    var segmentMinutes: Int
        get() = sp.getInt("segMin", 3)
        set(v) = sp.edit().putInt("segMin", v).apply()

    /** Loop recording limit. Oldest clips are deleted above this size. */
    var maxStorageGb: Int
        get() = sp.getInt("maxGb", 16)
        set(v) = sp.edit().putInt("maxGb", v).apply()

    /** Index into Storage.externalRoots(ctx): external USB hard drives only. */
    var storageIndex: Int
        get() = sp.getInt("storage", 0)
        set(v) = sp.edit().putInt("storage", v).apply()

    var recordAudio: Boolean
        get() = sp.getBoolean("audio", false)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean("boot", false)
        set(v) = sp.edit().putBoolean("boot", v).apply()

    var phoneAccess: Boolean
        get() = sp.getBoolean("server", true)
        set(v) = sp.edit().putBoolean("server", v).apply()

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    /** Set while the user wants recording on, so a restart can resume it. */
    var recordingWanted: Boolean
        get() = sp.getBoolean("wanted", false)
        set(v) = sp.edit().putBoolean("wanted", v).apply()
}
