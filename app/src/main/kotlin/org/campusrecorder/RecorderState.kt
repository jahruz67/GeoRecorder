package org.campusrecorder

import android.content.Context
import android.os.Build
import java.io.File

data class LiveState(
    val state: String="ready", val sessionId: String?=null, val durationNs: Long=0,
    val observations: Long=0, val bytes: Long=0, val available: Long=0,
    val battery: Int=-1, val charging: Boolean=false, val accuracy: Float?=null,
    val sources: Int=0, val dropped: Long=0, val queued: Int=0,
    val warnings: List<String> = emptyList(), val sensorSummary: List<String> = emptyList()
)
object RecorderState {
    @Volatile var live=LiveState()
    val recoveryLock=Any()
    fun root(context: Context)=File(context.filesDir,"sessions").apply { mkdirs() }
    fun device(): Map<String,Any> = mapOf("manufacturer" to Build.MANUFACTURER,"model" to Build.MODEL,
        "device" to Build.DEVICE,"hardware" to Build.HARDWARE,"board" to Build.BOARD,
        "android_release" to Build.VERSION.RELEASE,"api" to Build.VERSION.SDK_INT,
        "build_fingerprint" to Build.FINGERPRINT,"abis" to Build.SUPPORTED_ABIS.toList(),
        "app_version" to BuildConfig.VERSION_NAME,"coordinate_reference" to "EPSG:4326",
        "altitude_reference" to "Location.altitude: WGS84 ellipsoid; MSL only when explicitly marked",
        "pressure_interpretation" to "hPa; relative changes only; no floor inference")
}
