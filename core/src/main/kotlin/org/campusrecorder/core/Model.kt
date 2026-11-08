package org.campusrecorder.core

import com.google.protobuf.ByteString
import org.campusrecorder.proto.Recording.*
import java.util.concurrent.atomic.AtomicLong

fun field(name: String, value: Any): Field {
    val v = Value.newBuilder()
    when (value) {
        is ByteArray -> v.blob = ByteString.copyFrom(value)
        is Boolean -> v.flag = value
        is Float -> v.real = value.toDouble()
        is Double -> v.real = value
        is Number -> v.integer = value.toLong()
        else -> v.text = value.toString()
    }
    return Field.newBuilder().setName(name).setValue(v).build()
}
fun fields(vararg pairs: Pair<String, Any?>): List<Field> = pairs.mapNotNull { (k,v) -> v?.let { field(k,it) } }
fun group(name: String, vararg pairs: Pair<String, Any?>): Group =
    Group.newBuilder().setName(name).addAllFields(fields(*pairs)).build()

data class SessionMetadata(
    val schemaVersion: Int = 1,
    val id: String,
    val startedWallMs: Long,
    var endedWallMs: Long? = null,
    var state: String = "recording",
    var activeDurationNs: Long = 0,
    var observationCount: Long = 0,
    var droppedObservations: Long = 0,
    var receivedObservations: Long = 0,
    var acceptedObservations: Long = 0,
    var droppedBySource: MutableMap<String,Long> = mutableMapOf(),
    var chunkCount: Int = 0,
    var bytes: Long = 0,
    val synthetic: Boolean = false,
    val device: Map<String, Any> = emptyMap(),
    var notes: MutableList<String> = mutableListOf()
)

/** Delivery metrics describe timestamps actually observed, including regressions and gaps.
 * A gap is diagnostic, not a claim of a known number of hardware drops. */
class SourceMeter {
    var count = 0L; private set
    var firstNs = 0L; private set
    var lastNs = 0L; private set
    var regressions = 0L; private set
    var maxGapNs = 0L; private set
    var minGapNs = Long.MAX_VALUE; private set
    private var segmentStart=true
    private var intervalNs=0L
    private var intervals=0L
    fun beginSegment() { segmentStart=true }
    fun add(ns: Long) {
        if (count == 0L) firstNs = ns
        if (!segmentStart) {
            val gap = ns-lastNs
            if (gap < 0) regressions++ else {
                maxGapNs = maxOf(maxGapNs,gap); minGapNs = minOf(minGapNs,gap)
                intervalNs+=gap; intervals++
            }
        }
        segmentStart=false; lastNs = ns; count++
    }
    fun hz(): Double? = if (intervals>0 && intervalNs>0) intervals*1e9/intervalNs else null
    fun toFields() = fields("count" to count,"first_ns" to firstNs,"last_ns" to lastNs,
        "observed_hz" to hz(),"timestamp_regressions" to regressions,"maximum_gap_ns" to maxGapNs,
        "minimum_gap_ns" to minGapNs.takeIf { it != Long.MAX_VALUE },"valid_interval_count" to intervals,
        "valid_interval_duration_ns" to intervalNs,"rate_definition" to "nonnegative hardware timestamp intervals; intentional pause gaps excluded")
}

class InsufficientStorage(message: String) : java.io.IOException(message)
class IntegrityException(message: String) : java.io.IOException(message)
