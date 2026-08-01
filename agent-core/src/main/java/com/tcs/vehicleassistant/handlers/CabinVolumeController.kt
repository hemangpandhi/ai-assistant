package com.tcs.vehicleassistant.handlers

import android.car.Car
import android.car.media.CarAudioManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log

/**
 * AAOS-aware media volume read/write.
 *
 * On Automotive, [AudioManager.setStreamVolume] for [AudioManager.STREAM_MUSIC] is often a
 * silent no-op; zone/group volume via [CarAudioManager] is the supported path.
 */
object CabinVolumeController {

    private const val TAG = "CabinVolumeController"

    data class Levels(val current: Int, val max: Int, val source: String)

    fun read(context: Context): Levels {
        tryCar(context)?.let { return it.first }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return Levels(cur, max, "STREAM_MUSIC")
    }

    /**
     * Sets media volume to [targetIndex] (clamped). Returns the readback level after apply.
     */
    fun write(context: Context, targetIndex: Int): Levels {
        val before = read(context)
        val target = targetIndex.coerceIn(0, before.max.coerceAtLeast(0))
        if (target == before.current) return before

        val carApplied = tryWriteCar(context, target)
        if (carApplied != null && carApplied.current != before.current) {
            Log.i(TAG, "CarAudio applied ${before.current}->${carApplied.current} (max=${carApplied.max})")
            return carApplied
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flags = AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, flags)
        } catch (e: Exception) {
            Log.w(TAG, "setStreamVolume failed", e)
        }
        var after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (after == before.current) {
            // Last resort: step ADJUST_RAISE/LOWER (some images honor this when absolute set fails).
            val direction = if (target > before.current) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            val steps = kotlin.math.abs(target - before.current).coerceAtMost(before.max.coerceAtLeast(1))
            repeat(steps) {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags)
                } catch (e: Exception) {
                    Log.w(TAG, "adjustStreamVolume failed", e)
                    return@repeat
                }
            }
            after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)
        val result = Levels(after.coerceIn(0, max), max, "STREAM_MUSIC")
        Log.i(TAG, "AudioManager applied ${before.current}->${result.current} (wanted=$target)")
        return result
    }

    private fun tryCar(context: Context): Pair<Levels, CarAudioManager>? {
        return try {
            val car = Car.createCar(context) ?: return null
            val cam = car.getCarManager(Car.AUDIO_SERVICE) as? CarAudioManager ?: return null
            val zoneId = primaryZoneId(cam)
            val groupId = mediaGroupId(cam, zoneId) ?: return null
            val maxMethod = cam.javaClass.getMethod("getGroupMaxVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val max = (maxMethod.invoke(cam, zoneId, groupId) as Int).coerceAtLeast(0)
            val curMethod = cam.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val cur = (curMethod.invoke(cam, zoneId, groupId) as Int).coerceIn(0, max)
            Pair(Levels(cur, max, "CarAudio zone=$zoneId group=$groupId"), cam)
        } catch (e: Exception) {
            Log.w(TAG, "CarAudio read unavailable", e)
            null
        }
    }

    private fun tryWriteCar(context: Context, target: Int): Levels? {
        return try {
            val car = Car.createCar(context) ?: return null
            val cam = car.getCarManager(Car.AUDIO_SERVICE) as? CarAudioManager ?: return null
            val zoneId = primaryZoneId(cam)
            val groupId = mediaGroupId(cam, zoneId) ?: return null
            val maxMethod = cam.javaClass.getMethod("getGroupMaxVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val max = (maxMethod.invoke(cam, zoneId, groupId) as Int).coerceAtLeast(0)
            val clamped = target.coerceIn(0, max)
            val setMethod = cam.javaClass.getMethod("setGroupVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setMethod.invoke(cam, zoneId, groupId, clamped, 0)
            val curMethod = cam.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val applied = (curMethod.invoke(cam, zoneId, groupId) as Int).coerceIn(0, max)
            Levels(applied, max, "CarAudio zone=$zoneId group=$groupId")
        } catch (e: SecurityException) {
            Log.w(TAG, "CarAudio write denied — need CAR_CONTROL_AUDIO_VOLUME?", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "CarAudio write failed", e)
            null
        }
    }

    private fun primaryZoneId(cam: CarAudioManager): Int {
        return try {
            // API 30+ constant; fall back to 0.
            cam.javaClass.getField("PRIMARY_AUDIO_ZONE").getInt(null)
        } catch (_: Throwable) {
            0
        }
    }

    private fun mediaGroupId(cam: CarAudioManager, zoneId: Int): Int? {
        return try {
            val usage = AudioAttributes.USAGE_MEDIA
            // Prefer zone-aware API when present.
            try {
                val method = cam.javaClass.getMethod(
                    "getVolumeGroupIdForUsage",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                (method.invoke(cam, zoneId, usage) as Int).takeIf { it >= 0 }
            } catch (_: NoSuchMethodException) {
                val fallbackMethod = cam.javaClass.getMethod("getVolumeGroupIdForUsage", Int::class.javaPrimitiveType)
                (fallbackMethod.invoke(cam, usage) as Int).takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "media group id lookup failed", e)
            // Common default: group 0 is media on many AAOS images.
            0
        }
    }
}
