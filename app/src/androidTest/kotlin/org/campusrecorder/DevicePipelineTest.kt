package org.campusrecorder

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.campusrecorder.core.*
import org.campusrecorder.proto.Recording.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on a device/emulator; does not pretend emulated sensors validate physical hardware. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class DevicePipelineTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Test
    fun testRealSensorRegistrationAndUnavailableHardware() {
        val context=instrumentation.targetContext
        val thread=HandlerThread("test-sensors").apply { start() }
        val received=mutableListOf<Observation>()
        val latch=CountDownLatch(1)
        val collector=MotionCollector(context,Handler(thread.looper)) { synchronized(received) { received.add(it) }; if(it.kind==Kind.SENSOR) latch.countDown() }
        val started=CountDownLatch(1)
        Handler(thread.looper).post { collector.start(); started.countDown() }
        assertTrue(started.await(10,TimeUnit.SECONDS))
        val manager=context.getSystemService(SensorManager::class.java)
        if(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null) assertTrue("Expected an accelerometer callback",latch.await(15,TimeUnit.SECONDS))
        val stopped=CountDownLatch(1); Handler(thread.looper).post { collector.stop(); stopped.countDown() }
        assertTrue(stopped.await(10,TimeUnit.SECONDS)); thread.quitSafely()
        assertTrue(received.any { it.kind==Kind.CAPABILITY })
        MotionCollector.types.keys.filter { manager.getDefaultSensor(it)==null }.forEach { type ->
            assertTrue("Missing capability for unsupported sensor $type",received.any { it.sourceId=="sensor:$type:-1" })
        }
    }
    @Test fun testFrameworkSnapshotPreservesNanosecondIntegers() {
        val location=android.location.Location("gps").apply { elapsedRealtimeNanos=Long.MAX_VALUE-100; altitude=1.25 }
        val s=AndroidSnapshot.read(location).fields.associateBy { it.name }
        assertEquals(Long.MAX_VALUE-100,s["getElapsedRealtimeNanos"]!!.value.integer)
        assertEquals(1.25,s["getAltitude"]!!.value.real,0.0)
        assertFalse(s.containsKey("getSpeed"))
    }
    @Test fun testDeviceDiskRecoveryExportAndPause() {
        val context=instrumentation.targetContext
        val root=File(context.cacheDir,"test-${System.nanoTime()}").apply { mkdirs() }
        val store=SessionStore.create(root,System.currentTimeMillis(),true)
        val engine=RecordingEngine(store)
        repeat(5000) { assertTrue(engine.submit(syntheticRecord(it.toLong()))) }
        engine.transition("paused",1000); assertEquals("paused",SessionStore.readMetadata(store.directory).state)
        engine.transition("recording",1000)
        repeat(5000) { assertTrue(engine.submit(syntheticRecord((5000+it).toLong()))) }
        engine.transition("stopped",2000); engine.awaitClosed()
        assertEquals(10000L,SessionStore.readMetadata(store.directory).observationCount)
        val bytes=ByteArrayOutputStream()
        context.assets.open("schema/recording.proto").use { ArchiveExporter.export(listOf(store.directory),it,bytes) }
        assertTrue(bytes.size()>1000)
        assertTrue(SessionStore.recover(root).isEmpty())
        root.deleteRecursively()
    }
    @Test fun testPermissionDeniedRadioCollectorDoesNotCrash() {
        val context=object: android.content.ContextWrapper(instrumentation.targetContext) {
            override fun checkSelfPermission(permission: String)=android.content.pm.PackageManager.PERMISSION_DENIED
        }
        val thread=HandlerThread("test-radios").apply { start() }
        val received=mutableListOf<Observation>()
        val done=CountDownLatch(1)
        Handler(thread.looper).post {
            val c=RadioCollector(context,Handler(thread.looper)) { received.add(it) }
            c.start(); c.stop(); done.countDown()
        }
        assertTrue(done.await(15,TimeUnit.SECONDS)); thread.quitSafely()
        assertTrue(received.any { it.kind==Kind.CAPABILITY })
    }
    @Test fun testForegroundServiceScreenOffPauseResumeAndStop() {
        val context=instrumentation.targetContext
        for(permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,permission)
        }
        val activity=instrumentation.startActivitySync(android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(2000)
        context.startForegroundService(android.content.Intent(context,RecordingService::class.java).setAction(RecordingService.START))
        awaitState("recording")
        val session=RecorderState.live.sessionId!!
        SystemClock.sleep(4000)
        val before=RecorderState.live.observations
        instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
        val offStart=SystemClock.elapsedRealtimeNanos()
        SystemClock.sleep(12000)
        val offEnd=SystemClock.elapsedRealtimeNanos()
        assertEquals("recording",RecorderState.live.state)
        assertTrue("Observations must continue with screen off",RecorderState.live.observations>before)
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        context.startService(android.content.Intent(context,RecordingService::class.java).setAction(RecordingService.PAUSE))
        awaitState("paused")
        val pausedDuration=RecorderState.live.durationNs
        SystemClock.sleep(4000)
        assertEquals(pausedDuration,RecorderState.live.durationNs)
        instrumentation.runOnMainSync { activity.window.decorView.requestFocus() }
        context.startService(android.content.Intent(context,RecordingService::class.java).setAction(RecordingService.RESUME))
        awaitState("recording"); SystemClock.sleep(4000)
        context.startService(android.content.Intent(context,RecordingService::class.java).setAction(RecordingService.STOP))
        awaitState("stopped")
        val dir=File(RecorderState.root(context),session)
        val m=SessionStore.readMetadata(dir)
        var count=0L; var screenOffSensors=0L
        File(dir,"chunks").listFiles()!!.filter { it.name.endsWith(".pb.gz") }.forEach { file ->
            ChunkIO.scan(file) { o -> count++; if(o.kind==Kind.SENSOR && o.monotonicNs in offStart..offEnd) screenOffSensors++ }
        }
        assertEquals(m.observationCount,count)
        val manager=context.getSystemService(SensorManager::class.java)
        if(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null) assertTrue("Real sensor callbacks required during screen off",screenOffSensors>0)
        instrumentation.runOnMainSync { activity.finish() }
    }
    private fun awaitState(state: String) {
        val end=SystemClock.elapsedRealtime()+30000
        while(RecorderState.live.state!=state && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100)
        assertEquals(state,RecorderState.live.state)
    }
}
