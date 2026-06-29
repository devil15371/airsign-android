package com.airsign.utils

import android.content.Context
import android.content.SharedPreferences

object StorageHelper {
    private const val PREF_NAME = "airsign_prefs"
    private const val KEY_ENROLLED = "is_enrolled"
    private const val KEY_GESTURE = "enrolled_gesture_template"
    private const val KEY_PUF = "enrolled_puf_fingerprint"
    private const val KEY_THRESHOLD = "gesture_threshold"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveEnrollment(
        context: Context,
        gestureTemplate: List<List<Float>>,
        pufFingerprint: String,
        gestureThreshold: Float
    ) {
        val serializedGesture = gestureTemplate.joinToString(";") { pt ->
            pt.joinToString(",") { it.toString() }
        }
        
        getPrefs(context).edit().apply {
            putBoolean(KEY_ENROLLED, true)
            putString(KEY_GESTURE, serializedGesture)
            putString(KEY_PUF, pufFingerprint)
            putFloat(KEY_THRESHOLD, gestureThreshold)
            apply()
        }
    }

    fun isEnrolled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENROLLED, false)
    }

    fun getEnrolledGesture(context: Context): List<List<Float>>? {
        val raw = getPrefs(context).getString(KEY_GESTURE, null) ?: return null
        return try {
            raw.split(";").map { line ->
                line.split(",").map { it.toFloat() }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getEnrolledPUF(context: Context): String? {
        return getPrefs(context).getString(KEY_PUF, null)
    }

    fun getGestureThreshold(context: Context): Float {
        return getPrefs(context).getFloat(KEY_THRESHOLD, 0.22f)
    }

    fun clearEnrollment(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
