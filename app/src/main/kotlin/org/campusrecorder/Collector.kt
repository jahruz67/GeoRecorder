package org.campusrecorder

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock
import org.campusrecorder.core.*
import org.campusrecorder.proto.Recording.*

/** All collectors emit on the acquisition handler. The storage engine runs on another thread. */
abstract class Collector(val context: Context, val handler: Handler, val emit: (Observation)->Unit) {
    abstract fun start()
    abstract fun stop()
    fun permitted(permission: String)=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    fun event(kind: Kind, source: String, time: Long=SystemClock.elapsedRealtimeNanos(),
        properties: List<Field> = emptyList(), groups: List<Group> = emptyList(), sample: SensorSample?=null) {
        val b=Observation.newBuilder().setKind(kind).setSourceId(source).setMonotonicNs(time)
            .setReceivedMonotonicNs(SystemClock.elapsedRealtimeNanos()).addAllFields(properties).addAllGroups(groups)
        sample?.let { b.sensor=it }; emit(b.build())
    }
    fun status(source: String, message: String, available: Boolean=false) = event(Kind.CAPABILITY,source,
        properties=fields("available" to available,"message" to message))
    inline fun attempt(source: String, action: ()->Unit) {
        try { action() } catch(e: Exception) { status(source,"${e.javaClass.simpleName}: ${e.message}") }
    }
}
