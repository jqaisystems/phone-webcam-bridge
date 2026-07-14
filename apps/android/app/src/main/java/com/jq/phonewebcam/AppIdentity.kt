package com.jq.phonewebcam

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/**
 * Stable identity for this install: the PIN and device id are generated once
 * and reused across app launches so the PC bridge can reconnect automatically.
 */
object AppIdentity {
    private const val PREFS = "phone_webcam_identity"
    private const val KEY_PIN = "pin"
    private const val KEY_DEVICE_ID = "device_id"

    fun pin(context: Context): String = getOrCreate(context, KEY_PIN) {
        (100000 + SecureRandom().nextInt(900000)).toString()
    }

    fun deviceId(context: Context): String = getOrCreate(context, KEY_DEVICE_ID) {
        UUID.randomUUID().toString()
    }

    private fun getOrCreate(context: Context, key: String, generate: () -> String): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { return it }
        val value = generate()
        prefs.edit().putString(key, value).apply()
        return value
    }
}
