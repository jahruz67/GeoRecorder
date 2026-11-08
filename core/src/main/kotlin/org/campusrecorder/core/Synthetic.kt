package org.campusrecorder.core

import org.campusrecorder.proto.Recording.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sin

/** Desktop-only deterministic generator. Never used by the Android production recording path. */
fun syntheticRecord(i: Long): Observation {
    val ns=1_000_000_000L+i*2_500_000L
    return Observation.newBuilder().setSourceId("synthetic:accelerometer").setKind(Kind.SENSOR)
        .setMonotonicNs(ns).setReceivedMonotonicNs(ns+123_000L)
        .setSensor(SensorSample.newBuilder().setAndroidType(1).setAccuracy(3)
            .addAllValues(listOf(sin(i*.03).toFloat(),sin(i*.05).toFloat(),9.80665f)))
        .addAllFields(fields("synthetic" to true)).build()
}
fun main(args: Array<String>) {
    val destination=File(args.firstOrNull() ?: "deliverables").apply { mkdirs() }
    val root=File(destination,"synthetic-sessions")
    if(root.exists()) check(root.deleteRecursively()) { "Cannot clear old synthetic test sessions" }
    root.mkdirs()
    val store=SessionStore.create(root,1_800_000_000_000L,true,mapOf("model" to "SYNTHETIC GENERATOR; NOT DEVICE DATA"))
    val engine=RecordingEngine(store,capacity=16384)
    val start=System.nanoTime(); val runtime=Runtime.getRuntime()
    var peak=0L
    val n=240000L // 10 minutes of logical 400 Hz motion, processed faster than real time.
    for(i in 0 until n) {
        // Pace the producer below the bounded queue limit; a separate test intentionally overloads it.
        while(engine.pending>8000) Thread.sleep(1)
        check(engine.submit(syntheticRecord(i))) { "Synthetic producer rejected: ${engine.failure}; dropped=${engine.dropped.get()}" }
        if(i%1000==0L) peak=maxOf(peak,runtime.totalMemory()-runtime.freeMemory())
    }
    engine.transition("paused",n/2*2_500_000L)
    engine.transition("recording",n/2*2_500_000L)
    engine.transition("stopped",n*2_500_000L); engine.awaitClosed()
    val elapsed=System.nanoTime()-start
    val fixtures=SessionStore.create(root,1_800_000_000_000L,true,mapOf("model" to "SYNTHETIC ALL-KIND SCHEMA FIXTURES"))
    val fixtureEngine=RecordingEngine(fixtures)
    Kind.entries.filter { it!=Kind.UNRECOGNIZED && it.number in 1..14 }.forEach { kind ->
        val b=syntheticRecord(kind.number.toLong()).toBuilder().clearSensor().setSourceId("synthetic:${kind.name}").setKind(kind)
        when(kind) {
            Kind.SENSOR -> b.sensor=syntheticRecord(0).sensor
            Kind.LOCATION -> b.addAllFields(fields("getLatitude" to 0.0,"getLongitude" to 0.0,"getAccuracy" to 5.0,
                "getAltitude" to 10.0,"coordinate_reference" to "EPSG:4326","altitude_reference" to "WGS84 ellipsoid"))
            Kind.GNSS_RAW -> {
                b.addGroups(group("clock","getTimeNanos" to 1_234_567_890_123_456_789L,"hasFullBiasNanos" to true,"getFullBiasNanos" to -1_000_000_000_000L))
                b.addGroups(group("getMeasurements[0]","getSvid" to 1,"getConstellationType" to 1,"getCn0DbHz" to 35.25,
                    "getReceivedSvTimeNanos" to 123456789L,"getReceivedSvTimeUncertaintyNanos" to 10L,"getAccumulatedDeltaRangeState" to 1))
            }
            Kind.GNSS_SATELLITES -> b.addGroups(group("satellite","svid" to 1,"cn0_dbhz" to 35.25,"used_in_fix" to true))
            Kind.GNSS_NAVIGATION -> b.addAllFields(fields("getData" to byteArrayOf(0,1,2,-1),"getStatus" to 1,"getSvid" to 1))
            Kind.GNSS_NMEA -> b.addAllFields(fields("sentence" to "SYNTHETIC NMEA FIXTURE; NOT A VALID FIX","nmea_wall_ms" to 1_800_000_000_000L))
            Kind.WIFI_SCAN -> b.addGroups(group("access_point","bssid" to "00:00:00:00:00:00","rssi_dbm" to -60,"frequency_mhz" to 2412,"timestamp_us" to 1000000L))
            Kind.BLE_SCAN -> b.addAllFields(fields("address" to "00:00:00:00:00:00","rssi_dbm" to -70,"advertisement" to byteArrayOf(2,1,6)))
            Kind.WIFI_RTT -> b.addGroups(group("ranging_result","getStatus" to 0,"getDistanceMm" to 1234,"getDistanceStdDevMm" to 100,"getRangingTimestampMillis" to 1000L))
            Kind.CLOCK_ANCHOR -> b.addAllFields(fields("wall_utc_ms" to 1_800_000_000_000L,"elapsed_before_ns" to 1_000_000_000L,"elapsed_after_ns" to 1_000_001_000L))
            Kind.LIFECYCLE -> b.addAllFields(fields("action" to "synthetic_fixture"))
            Kind.HEALTH -> b.addAllFields(fields("free_storage_bytes" to 100_000_000_000L,"battery_impact" to "not a device measurement"))
            Kind.CAPABILITY -> b.addAllFields(fields("available" to false,"message" to "Synthetic unavailable hardware fixture"))
            Kind.SOURCE_STATS -> b.addAllFields(fields("observed_hz" to 400.0,"count" to 240000L))
            else -> Unit
        }
        check(fixtureEngine.submit(b.build()))
    }
    fixtureEngine.transition("stopped",0); fixtureEngine.awaitClosed()
    val schema=File("schema/recording.proto").takeIf { it.exists() } ?: File("../schema/recording.proto")
    FileOutputStream(File(destination,ArchiveExporter.FILE_NAME)).use { out -> FileInputStream(schema).use { ArchiveExporter.export(listOf(store.directory,fixtures.directory),it,out) } }
    check(root.deleteRecursively()) { "Cannot remove temporary synthetic sessions after export" }
    val report=mapOf("synthetic" to true,"observations" to n,"logical_duration_seconds" to 600,
        "elapsed_seconds" to elapsed/1e9,"observations_per_second" to n*1e9/elapsed,
        "committed_bytes" to store.metadata.bytes,"compressed_bytes_per_observation" to store.metadata.bytes.toDouble()/n,
        "compressed_write_bytes_per_second" to store.metadata.bytes*1e9/elapsed,
        "peak_sampled_jvm_used_bytes" to peak,"queue_drops" to engine.dropped.get(),
        "additional_schema_fixture_observations" to 14,
        "environment" to System.getProperty("java.vm.name"),"battery_impact" to "Not measurable on desktop; use on-device health energy/current readings")
    File(destination,"synthetic-benchmark.json").writeText(SessionStore.gson.toJson(report))
    println(SessionStore.gson.toJson(report))
}
