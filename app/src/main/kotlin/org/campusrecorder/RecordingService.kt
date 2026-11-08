package org.campusrecorder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import org.campusrecorder.core.*
import org.campusrecorder.proto.Recording.*
import java.util.Locale

class RecordingService : Service() {
    companion object {
        const val START="org.campusrecorder.START"
        const val PAUSE="org.campusrecorder.PAUSE"
        const val RESUME="org.campusrecorder.RESUME"
        const val STOP="org.campusrecorder.STOP"
        private const val CHANNEL="recording"
        private const val NOTICE=10
    }
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var engine: RecordingEngine?=null
    private var collectors=emptyList<Collector>()
    private val meters=linkedMapOf<String,SourceMeter>()
    private val lastSeen=mutableMapOf<String,Long>()
    private val warnings=linkedSetOf<String>()
    private val statuses=linkedMapOf<String,String>()
    private var wake: PowerManager.WakeLock?=null
    private var activeStart=0L
    private var accumulated=0L
    private var paused=false
    private var ending=false
    private var locationAccuracy: Float?=null
    private var locationReceivedNs=0L
    private var batteryStartEnergy: Long?=null
    private var tickCount=0
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Recording status",NotificationManager.IMPORTANCE_LOW))
        thread=HandlerThread("campus-acquisition",Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        handler=Handler(thread.looper)
    }
    override fun onBind(intent: Intent?) : IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val action=intent?.action ?: return START_NOT_STICKY
        if(action==START) {
            if(engine==null) RecorderState.live=LiveState(state="initializing")
            // Called from visible Activity after runtime permission decision. Never auto-start on boot or restart.
            try {
                if(Build.VERSION.SDK_INT>=29) startForeground(NOTICE,notification("Preparing recording…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                else startForeground(NOTICE,notification("Preparing recording…"))
            }
            catch(e: Exception) { RecorderState.live=LiveState(warnings=listOf("Cannot start location service: ${e.message}")); stopSelf(); return START_NOT_STICKY }
        }
        handler.post {
            try {
                when(action) {
                    START -> if(engine==null && !ending) begin(intent?.getStringExtra("previous_session_id"))
                    PAUSE -> pause()
                    RESUME -> resume()
                    STOP -> finish("stopped")
                }
            } catch(e: Exception) { fail("${e.javaClass.simpleName}: ${e.message}") }
        }
        return START_NOT_STICKY
    }
    private fun begin(previous: String?) {
        val store=synchronized(RecorderState.recoveryLock) {
            SessionStore.recover(RecorderState.root(this))
            SessionStore.create(RecorderState.root(this),System.currentTimeMillis(),device=RecorderState.device())
        }
        engine=RecordingEngine(store)
        activeStart=SystemClock.elapsedRealtimeNanos()
        anchor()
        lifecycle("start",fields("previous_session_id" to previous,"boot_count" to android.provider.Settings.Global.getInt(contentResolver,"boot_count",-1),
            "directory_fsync_available" to ChunkIO.syncDirectory(filesDir)))
        acquireWake()
        collectors=listOf(MotionCollector(this,handler,::emit),GnssCollector(this,handler,::emit) {
            locationAccuracy=it; locationReceivedNs=SystemClock.elapsedRealtimeNanos()
        },RadioCollector(this,handler,::emit))
        startCollectors()
        handler.post(tick)
    }
    private fun startCollectors() {
        collectors.forEach { c -> runCatching { c.start() }.onFailure {
            val message="${c.javaClass.simpleName}: ${it.javaClass.simpleName}: ${it.message}"
            warnings.add(message)
            record(Kind.CAPABILITY,"collector:${c.javaClass.simpleName}",fields("available" to false,"message" to message))
        } }
    }
    private fun emit(observation: Observation) {
        if(ending || paused && observation.kind !in listOf(Kind.LIFECYCLE,Kind.CLOCK_ANCHOR,Kind.HEALTH,Kind.SOURCE_STATS)) return
        val now=SystemClock.elapsedRealtimeNanos()
        if(observation.kind in listOf(Kind.SENSOR,Kind.LOCATION,Kind.GNSS_RAW,Kind.GNSS_SATELLITES,Kind.GNSS_NAVIGATION,Kind.GNSS_NMEA,Kind.WIFI_SCAN,Kind.BLE_SCAN,Kind.WIFI_RTT)) {
            meters.getOrPut(observation.sourceId) { SourceMeter() }.add(observation.monotonicNs)
            lastSeen[observation.sourceId]=now
        }
        if(observation.kind==Kind.CAPABILITY) {
            val f=observation.fieldsList.associateBy { it.name }
            val message=f["message"]?.value?.text
            if(message!=null) {
                statuses[observation.sourceId]=message
                if(f["available"]?.value?.flag==false && ("denied" in message || "disabled" in message || "failed" in message || "rejected" in message)) warnings.add("${observation.sourceId}: $message")
            }
        }
        engine?.submit(observation)
    }
    private fun record(kind: Kind,source: String,properties: List<Field>) {
        val now=SystemClock.elapsedRealtimeNanos()
        emit(Observation.newBuilder().setKind(kind).setSourceId(source).setMonotonicNs(now).setReceivedMonotonicNs(now).addAllFields(properties).build())
    }
    private fun lifecycle(action: String,properties: List<Field> = emptyList())=record(Kind.LIFECYCLE,"lifecycle",fields("action" to action)+properties)
    private fun anchor() {
        val before=SystemClock.elapsedRealtimeNanos(); val wall=System.currentTimeMillis(); val after=SystemClock.elapsedRealtimeNanos()
        record(Kind.CLOCK_ANCHOR,"clock",fields("wall_utc_ms" to wall,"elapsed_before_ns" to before,
            "elapsed_after_ns" to after,"wall_resolution_ms" to 1,"uptime_ms" to SystemClock.uptimeMillis(),
            "note" to "Wall clock may jump; bracket quantifies read interval, not clock accuracy"))
    }
    private fun duration(): Long=accumulated+if(paused || activeStart==0L) 0L else SystemClock.elapsedRealtimeNanos()-activeStart
    private fun acquireWake() {
        if(wake==null) wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CampusRecorder:acquisition").apply { setReferenceCounted(false) }
        wake!!.acquire(10*60*1000L) // Renewed in health tick; bounded even if service cleanup fails.
    }
    private fun pause() {
        val e=engine ?: return
        if(paused || ending) return
        collectors.forEach { it.stop() }
        accumulated=duration(); paused=true
        lifecycle("pause"); emitStats()
        e.transition("paused",accumulated)
        if(wake?.isHeld==true) wake?.release()
        updateLive()
    }
    private fun resume() {
        val e=engine ?: return
        if(!paused || ending) return
        // Resume is exposed only from a visible Activity; no background relaunch.
        paused=false; activeStart=SystemClock.elapsedRealtimeNanos(); e.transition("recording",accumulated)
        meters.values.forEach { it.beginSegment() }
        anchor(); lifecycle("resume"); acquireWake(); startCollectors(); updateLive()
    }
    private fun emitStats() {
        val e=engine ?: return
        meters.forEach { (source,meter) -> record(Kind.SOURCE_STATS,source,meter.toFields()+fields(
            "queue_drops" to (e.sourceDrops[source]?.get() ?: 0L),"statistics_scope" to "session; intentional pause gaps excluded from sampling rates")) }
    }
    private fun finish(state: String) {
        if(ending) return
        val e=engine
        if(e!=null) {
            collectors.forEach { runCatching { it.stop() } }
            val ns=duration()
            runCatching { e.flush(ns) }.onFailure { warnings.add("Final queue drain: ${it.message}") }
            lifecycle("stop",fields("reason" to state)); emitStats(); anchor()
            ending=true; handler.removeCallbacks(tick)
            runCatching { e.transition(state,ns); e.awaitClosed() }.onFailure { warnings.add("Final checkpoint: ${it.message}") }
            runCatching { SessionStore.recover(RecorderState.root(this)) }.onFailure { warnings.add("Recovery: ${it.message}") }
            RecorderState.live=RecorderState.live.copy(state=if(e.failure==null) state else "error",durationNs=ns,
                observations=e.store.metadata.observationCount,bytes=e.store.metadata.bytes,dropped=e.dropped.get(),warnings=warnings.toList()+listOfNotNull(e.failure))
        } else ending=true
        if(wake?.isHeld==true) wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun fail(message: String) {
        warnings.add(message)
        finish("error")
        RecorderState.live=RecorderState.live.copy(state="error",warnings=warnings.toList())
        getSystemService(NotificationManager::class.java).notify(11,notification("Recording stopped: $message",false))
    }
    private fun batteryFields(): List<Field> {
        val i=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val b=getSystemService(BatteryManager::class.java)
        val pm=getSystemService(PowerManager::class.java)
        val energy=b.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeIf { it!=Long.MIN_VALUE }
        if(batteryStartEnergy==null) batteryStartEnergy=energy
        return fields("level" to i?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),"scale" to i?.getIntExtra(BatteryManager.EXTRA_SCALE,-1),
            "plugged" to i?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0),"temperature_tenths_c" to i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0),
            "voltage_mv" to i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0),
            "current_ua" to b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it!=Int.MIN_VALUE },
            "charge_uah" to b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it!=Int.MIN_VALUE },
            "energy_nwh" to energy,"energy_change_nwh" to energy?.let { v -> batteryStartEnergy?.let { v-it } },
            "thermal_status" to if(Build.VERSION.SDK_INT>=29) pm.currentThermalStatus else null,
            "power_save" to pm.isPowerSaveMode,"interactive" to pm.isInteractive,
            "free_storage_bytes" to filesDir.usableSpace,"queue_size" to engine?.pending,
            "received" to engine?.received?.get(),"accepted" to engine?.accepted?.get(),"written" to engine?.written?.get(),
            "queue_drops" to engine?.dropped?.get(),"java_used_bytes" to (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()),
            "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),"active_duration_ns" to duration())
    }
    private val tick=object: Runnable {
        override fun run() {
            if(ending) return
            val e=engine ?: return
            e.failure?.let { fail(it); return }
            try {
                e.activeDurationNs=duration()
                if(tickCount%5==0) {
                    record(Kind.HEALTH,"health",batteryFields())
                    if(!paused) acquireWake()
                }
                if(tickCount%15==0) { anchor(); emitStats() }
                updateLive(); tickCount++
            } catch(ex: Exception) { fail("Health monitoring failed: ${ex.message}"); return }
            handler.postDelayed(this,2000)
        }
    }
    private fun updateLive() {
        val e=engine ?: return
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100) ?: 100
        val percent=if(level>=0 && scale>0) level*100/scale else -1
        val current=warnings.toMutableList()
        if(percent in 0..15) current.add("Battery is low ($percent%)")
        if(filesDir.usableSpace<128L*1024*1024) current.add("Storage is low; recording stops at 32 MiB reserve")
        if(e.dropped.get()>0) current.add("${e.dropped.get()} observations dropped by overloaded storage queue")
        val now=SystemClock.elapsedRealtimeNanos()
        if(!paused) {
            if(now-activeStart>20_000_000_000L && (lastSeen["gnss:raw"] ?: 0L)<now-20_000_000_000L) current.add("No recent raw GNSS; hardware, permissions, or indoor reception may limit data")
            lastSeen.filter { it.key.startsWith("sensor:") && it.key.split(':').getOrNull(1)?.toIntOrNull() in setOf(1,2,4,6,9,10,11,14,15,16,20,35) && now-it.value>15_000_000_000L }.forEach { current.add("${it.key}: no events for 15 seconds") }
        }
        RecorderState.live=LiveState(if(paused) "paused" else "recording",e.store.metadata.id,duration(),e.written.get(),
            e.store.metadata.bytes,filesDir.usableSpace,percent,(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)>0,
            locationAccuracy.takeIf { now-locationReceivedNs<30_000_000_000L },meters.size,e.dropped.get(),e.pending,current,
            meters.map { (source,m) -> "$source  ·  ${m.count} events  ·  ${m.hz()?.let { String.format(Locale.US,"%.1f Hz",it) } ?: "event driven"}" }+statuses.map { "${it.key}: ${it.value}" })
        getSystemService(NotificationManager::class.java).notify(NOTICE,notification("${if(paused) "Paused" else "Recording"} · ${e.written.get()} observations · ${duration()/1_000_000_000}s"))
    }
    private fun notification(text: String,recording: Boolean=true): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RecordingService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_recorder).setContentTitle("Campus Recorder")
            .setContentText(text).setContentIntent(open).setOngoing(recording).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
        if(recording) b.addAction(Notification.Action.Builder(null,"Stop",stop).build())
        return b.build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { handler.post { lifecycle("task_removed"); anchor() }; super.onTaskRemoved(rootIntent) }
    override fun onTrimMemory(level: Int) { handler.post { record(Kind.HEALTH,"system",fields("trim_memory_level" to level)) }; super.onTrimMemory(level) }
    override fun onDestroy() {
        handler.post { if(!ending) finish("interrupted"); thread.quitSafely() }
        super.onDestroy()
    }
}
