package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import com.tcs.vehicleassistant.core.CabinSnapshot
import com.tcs.vehicleassistant.core.NavSessionState
import com.tcs.vehicleassistant.LocationManager
import com.tcs.vehicleassistant.VehicleManager

object CabinSnapshotReader {

    fun capture(context: Context): CabinSnapshot {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVol)
        val pct = Math.round((curVol.toFloat() / maxVol) * 100f)

        val playing = try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(null).any { controller ->
                val state = controller.playbackState?.state
                state == android.media.session.PlaybackState.STATE_PLAYING ||
                    state == android.media.session.PlaybackState.STATE_BUFFERING
            }
        } catch (_: SecurityException) {
            // MEDIA_CONTENT_CONTROL may be missing on some images — treat as unknown/not playing.
            false
        } catch (_: Exception) {
            false
        }

        val gear = VehicleManager.getGearSelection()
        val fuelRaw = VehicleManager.getFuelLevel()
        val fuelPct = CabinSnapshot.normalizeFuelLevelPct(fuelRaw)

        val (lon, lat) = try {
            LocationManager.getCoordinates(context)
        } catch (_: Exception) {
            Pair(Double.NaN, Double.NaN)
        }
        val city = try {
            LocationManager.getCurrentCity(context).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        return CabinSnapshot(
            mediaVolumePct = pct,
            mediaPlaying = playing,
            fanLevel = VehicleManager.getRealFanSpeed().coerceAtLeast(0),
            fanMax = VehicleManager.getFanMaxLevel().coerceAtLeast(1),
            cabinTempF = VehicleManager.getRealTemperature(),
            seatHeaterLevel = VehicleManager.getRealSeatHeaterLevel().coerceAtLeast(0),
            acOn = VehicleManager.isHvacAcOn,
            hvacPowerOn = VehicleManager.isHvacPowerOn,
            hvacAutoOn = VehicleManager.isHvacAutoOn,
            defrostOn = VehicleManager.isDefrosterOn,
            speedMph = VehicleManager.getRealSpeed(),
            gear = gear,
            isParked = CabinSnapshot.gearLooksParked(gear),
            fuelLevelPct = fuelPct,
            windowOpenPct = VehicleManager.getMaxWindowOpenPct(),
            navActiveDest = NavSessionState.activeDest,
            city = city,
            latitude = lat.takeUnless { it.isNaN() },
            longitude = lon.takeUnless { it.isNaN() },
        )
    }
}
