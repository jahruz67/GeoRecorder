package org.campusrecorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.campusrecorder.proto.Recording.*
import org.campusrecorder.core.SessionStore
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/** Host-side Android framework tests. These do not certify screen-off hardware behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,36])
class AndroidBehaviorTest {
    private lateinit var app: Application
    @Before fun setup() { app=RuntimeEnvironment.getApplication(); RecorderState.live=LiveState() }
    @After fun cleanup() { RecorderState.live=LiveState() }
    @Test fun unavailableMotionHardwareIsExplicit() {
        val events=mutableListOf<Observation>()
        val collector=MotionCollector(app,Handler(Looper.getMainLooper())) { events.add(it) }
        collector.start(); collector.stop()
        assertTrue(events.any { it.sourceId=="sensor:6:-1" && it.fieldsList.any { f -> f.name=="message" && "unavailable" in f.value.text } })
        assertTrue(events.any { it.sourceId=="sensor:inventory" })
    }
    @Test fun frameworkLocationRetainsIntegerTimestampsAndOptionalValues() {
        val location=Location("gps").apply { elapsedRealtimeNanos=Long.MAX_VALUE-100; altitude=123.25; accuracy=3.5f }
        val fields=AndroidSnapshot.read(location).fields.associateBy { it.name }
        assertEquals(Long.MAX_VALUE-100,fields["getElapsedRealtimeNanos"]!!.value.integer)
        assertEquals(123.25,fields["getAltitude"]!!.value.real,0.0)
        assertFalse(fields.containsKey("getSpeed"))
        assertFalse(fields.containsKey("getBearing"))
    }
    @Test fun revokedRadioPermissionsBecomeCapabilities() {
        val events=mutableListOf<Observation>()
        val denied=object: android.content.ContextWrapper(app) {
            override fun checkSelfPermission(permission: String)=android.content.pm.PackageManager.PERMISSION_DENIED
        }
        val collector=RadioCollector(denied,Handler(Looper.getMainLooper())) { events.add(it) }
        collector.start(); collector.stop()
        assertTrue(events.any { it.sourceId=="ble" && it.fieldsList.any { f -> f.name=="message" && "denied" in f.value.text } })
    }
    @Test fun homeHasRecordingControlsAndPermissionExplanation() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val root=activity.window.decorView
        assertTrue(allViews(root).filterIsInstance<Button>().any { it.text=="Start recording" })
        allViews(root).filterIsInstance<Button>().first { it.text=="Permissions & recording guide" }.performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun sensorSamplesCopyHardwareValuesAndAccuracyWithoutSmoothing() {
        val manager=app.getSystemService(SensorManager::class.java)
        val sensor=ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowOf(manager).addSensor(sensor)
        val records=mutableListOf<Observation>()
        val collector=MotionCollector(app,Handler(Looper.getMainLooper())) { records.add(it) }
        collector.start()
        val event=ShadowSensorManager.createSensorEvent(3)
        event.sensor=sensor; event.timestamp=12345678901234567L; event.accuracy=2
        event.values[0]=1.25f; event.values[1]=-2.75f; event.values[2]=Float.NaN
        collector.onSensorChanged(event)
        event.values[0]=99f
        val sample=records.last { it.kind==Kind.SENSOR }
        assertEquals(12345678901234567L,sample.monotonicNs)
        assertEquals(2,sample.sensor.accuracy)
        assertEquals(1.25f,sample.sensor.getValues(0),0f)
        assertEquals(-2.75f,sample.sensor.getValues(1),0f)
        assertTrue(sample.sensor.getValues(2).isNaN())
        collector.stop()
    }
    @Test fun foregroundServiceCommitsThroughPauseResumeAndStop() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        val controller=Robolectric.buildService(RecordingService::class.java).create()
        val service=controller.get()
        fun action(action: String) { service.onStartCommand(Intent(app,RecordingService::class.java).setAction(action),0,1) }
        fun await(state: String) {
            repeat(200) {
                if(RecorderState.live.state==state) return
                Thread.sleep(20)
            }
            assertEquals(RecorderState.live.warnings.joinToString("; "),state,RecorderState.live.state)
        }
        action(RecordingService.START); await("recording")
        val id=RecorderState.live.sessionId!!
        action(RecordingService.PAUSE); await("paused")
        assertEquals("paused",SessionStore.readMetadata(java.io.File(RecorderState.root(app),id)).state)
        action(RecordingService.RESUME); await("recording")
        action(RecordingService.STOP); await("stopped")
        val metadata=SessionStore.readMetadata(java.io.File(RecorderState.root(app),id))
        assertTrue(metadata.observationCount>0)
        assertEquals(metadata.acceptedObservations,metadata.observationCount)
        assertEquals(metadata.receivedObservations,metadata.acceptedObservations+metadata.droppedObservations)
        controller.destroy(); activity.pause().stop().destroy()
    }
    private fun allViews(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
}
