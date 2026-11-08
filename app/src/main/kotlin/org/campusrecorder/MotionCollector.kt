package org.campusrecorder

import android.Manifest
import android.content.Context
import android.hardware.*
import android.os.Build
import android.os.Handler
import org.campusrecorder.core.*
import org.campusrecorder.proto.Recording.*

class MotionCollector(context: Context, handler: Handler, emit: (Observation)->Unit) : Collector(context,handler,emit), SensorEventListener2 {
    private val manager=context.getSystemService(SensorManager::class.java)
    private val active=mutableMapOf<Sensor,String>()
    private var pressureReference: Float?=null
    private val highRate=setOf(1,2,4,10,14,16,35,38,39,40,41)
    companion object {
        val types=linkedMapOf(1 to "Accelerometer",35 to "Uncalibrated accelerometer",4 to "Gyroscope",16 to "Uncalibrated gyroscope",
            2 to "Magnetometer",14 to "Uncalibrated magnetometer",9 to "Gravity",10 to "Linear acceleration",
            11 to "Rotation vector",15 to "Game rotation vector",20 to "Geomagnetic rotation vector",6 to "Pressure",
            18 to "Step detector",19 to "Step counter",28 to "Pose 6DoF",36 to "Hinge angle",13 to "Ambient temperature",
            37 to "Head tracker",38 to "Limited axes accelerometer",39 to "Limited axes gyroscope",
            40 to "Limited axes uncalibrated accelerometer",41 to "Limited axes uncalibrated gyroscope",42 to "Heading")
    }
    override fun start() {
        types.forEach { (type,label) ->
            val sensor=manager?.getDefaultSensor(type)
            val source="sensor:$type:${sensor?.id ?: -1}"
            if(sensor==null) { status(source,"$label: hardware unavailable"); return@forEach }
            // One default sensor per type; don't activate deprecated orientation or every vendor virtual duplicate.
            val step=type==18 || type==19
            if(step && Build.VERSION.SDK_INT>=29 && !permitted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                status(source,"$label: activity recognition permission denied"); return@forEach
            }
            val period=if(type in highRate) maxOf(2500,sensor.minDelay) else if(sensor.reportingMode==Sensor.REPORTING_MODE_CONTINUOUS) maxOf(20000,sensor.minDelay) else 0
            event(Kind.CAPABILITY,source,properties=fields("name" to sensor.name,"vendor" to sensor.vendor,"version" to sensor.version,
                "android_type" to type,"string_type" to sensor.stringType,"id" to sensor.id,"resolution" to sensor.resolution,
                "maximum_range" to sensor.maximumRange,"power_ma" to sensor.power,"min_delay_us" to sensor.minDelay,
                "max_delay_us" to sensor.maxDelay,"fifo_max_count" to sensor.fifoMaxEventCount,
                "fifo_reserved_count" to sensor.fifoReservedEventCount,"wake_up" to sensor.isWakeUpSensor,
                "reporting_mode" to sensor.reportingMode,"requested_period_us" to period,"batch_latency_us" to 0))
            attempt(source) {
                if(manager.registerListener(this,sensor,period,0,handler)) { active[sensor]=source; status(source,"$label registered",true) }
                else status(source,"$label: registration rejected")
            }
        }
        val inventory=manager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        event(Kind.CAPABILITY,"sensor:inventory",groups=inventory.map { s -> group("sensor",
            "id" to s.id,"name" to s.name,"vendor" to s.vendor,"type" to s.type,"string_type" to s.stringType,
            "wake_up" to s.isWakeUpSensor,"power_ma" to s.power,"resolution" to s.resolution) })
        status("sensor:selection","Default independent physical/calibrated streams plus distinct fusion vectors; deprecated orientation, duplicate vendor sensors, light/proximity and motion triggers omitted")
    }
    override fun onSensorChanged(event: SensorEvent) {
        val source=active[event.sensor] ?: return
        val pressureFields=if(event.sensor.type==Sensor.TYPE_PRESSURE && event.values.isNotEmpty()) {
            if(pressureReference==null && event.values[0].isFinite()) pressureReference=event.values[0]
            fields("session_pressure_reference_hpa" to pressureReference,
                "derived_pressure_change_hpa" to pressureReference?.let { event.values[0].toDouble()-it.toDouble() },
                "pressure_note" to "Relative to first session pressure sample; weather/drift included; not floor elevation")
        } else emptyList()
        event(Kind.SENSOR,source,event.timestamp,properties=pressureFields,sample=SensorSample.newBuilder().setAndroidType(event.sensor.type)
            .setAccuracy(event.accuracy).addAllValues(event.values.map { it }).build())
    }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        event(Kind.CAPABILITY,active[sensor] ?: "sensor:${sensor.type}:${sensor.id}",properties=fields("accuracy" to accuracy,"event" to "accuracy_changed"))
    }
    override fun onFlushCompleted(sensor: Sensor) { status(active[sensor] ?: "sensor:${sensor.type}:${sensor.id}","flush_completed",true) }
    override fun stop() { manager?.unregisterListener(this); active.clear() }
}
