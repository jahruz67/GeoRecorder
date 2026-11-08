package org.campusrecorder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.*
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.*
import android.widget.*
import org.campusrecorder.core.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ink=Color.rgb(24,43,35)
    private val muted=Color.rgb(96,111,101)
    private val green=Color.rgb(20,107,80)
    private val page=Color.rgb(244,246,240)
    private val ui=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var recordTab: Button
    private lateinit var sessionsTab: Button
    private var screen="record"
    private var recovering=true
    private var exporting=false
    private var sessionFiles=emptyList<Pair<File,SessionMetadata>>()
    private var selectedExport=emptyList<File>()
    private var recoveryExport=false
    private var previousId: String?=null
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var count: TextView
    private lateinit var size: TextView
    private lateinit var storage: TextView
    private lateinit var battery: TextView
    private lateinit var accuracy: TextView
    private lateinit var health: TextView
    private lateinit var sensors: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private val refresh=object: Runnable {
        override fun run() { if(screen=="record") renderLive(); ui.postDelayed(this,1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen=savedInstanceState?.getString("screen") ?: "record"
        recoveryExport=savedInstanceState?.getBoolean("recoveryExport") ?: false
        selectedExport=savedInstanceState?.getStringArrayList("exportIds")?.map { File(RecorderState.root(this),it) } ?: emptyList()
        window.statusBarColor=page; window.navigationBarColor=page
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(page) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val system=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(dp(22),system.top,dp(22),system.bottom)
            } else v.setPadding(dp(22),insets.systemWindowInsetTop,dp(22),insets.systemWindowInsetBottom)
            insets
        }
        val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(24),0,dp(20)) }
        header.addView(text("CAMPUS  /  RECORDER",12,green,true).apply { letterSpacing=.16f })
        header.addView(text("Every walk is a discovery.",26,ink,true).apply { setPadding(0,dp(10),0,0) })
        root.addView(header)
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        recordTab=button("Record") { screen="record"; buildScreen() }
        sessionsTab=button("Sessions") { screen="sessions"; loadSessions() }
        tabs.addView(recordTab,LinearLayout.LayoutParams(0,dp(48),1f)); tabs.addView(sessionsTab,LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(tabs)
        val scroll=ScrollView(this).apply { isFillViewport=false; clipToPadding=false }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(20),0,dp(24)) }
        scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        buildScreen()
        if(screen=="sessions") loadSessions()
        io.execute {
            var recoveryError: String?=null
            synchronized(RecorderState.recoveryLock) {
                if(RecorderState.live.state !in listOf("recording","paused","initializing")) {
                    runCatching { SessionStore.recover(RecorderState.root(this)) }.onFailure { recoveryError="Recovery needs attention: ${it.message}" }
                }
            }
            ui.post { recovering=false; recoveryError?.let { RecorderState.live=RecorderState.live.copy(warnings=listOf(it)) }; renderLive() }
        }
    }
    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen",screen); outState.putBoolean("recoveryExport",recoveryExport)
        outState.putStringArrayList("exportIds",ArrayList(selectedExport.map { it.name }))
        super.onSaveInstanceState(outState)
    }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value: String,size: Int=15,color: Int=ink,bold: Boolean=false)=TextView(this).apply {
        this.text=value; textSize=size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun background(color: Int,radius: Int=20)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun button(label: String,action: ()->Unit)=Button(this).apply {
        text=label; isAllCaps=false; textSize=15f; setTextColor(green); background=background(Color.rgb(230,237,226),14)
        setOnClickListener { action() }; minHeight=dp(48)
    }
    private fun card(): LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(20)); background=background(Color.WHITE)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) }
    }
    private fun buildScreen() {
        recordTab.setTextColor(if(screen=="record") Color.WHITE else green); recordTab.background=background(if(screen=="record") green else Color.rgb(230,237,226),14)
        sessionsTab.setTextColor(if(screen=="sessions") Color.WHITE else green); sessionsTab.background=background(if(screen=="sessions") green else Color.rgb(230,237,226),14)
        body.removeAllViews()
        if(screen=="sessions") { buildSessions(); return }
        val main=card()
        status=text("●  READY TO RECORD",12,green,true).apply { letterSpacing=.08f }; main.addView(status)
        timer=text("00:00:00",48,ink).apply { typeface=Typeface.create("monospace",Typeface.NORMAL); setPadding(0,dp(14),0,0); contentDescription="Recording duration" }; main.addView(timer)
        main.addView(text("Walk freely. Keep every observation.",14,muted).apply { setPadding(0,dp(3),0,dp(20)) })
        primary=button("Start recording") { mainAction() }.apply { setTextColor(Color.WHITE); background=background(green,14) }
        main.addView(primary,LinearLayout.LayoutParams(-1,dp(58)))
        secondary=button("Stop and save") { confirmStop() }.apply { visibility=View.GONE }
        main.addView(secondary,LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(10) }); body.addView(main)
        val metrics=card()
        count=metric(metrics,"OBSERVATIONS","0"); size=metric(metrics,"RECORDING SIZE","0 B")
        storage=metric(metrics,"AVAILABLE STORAGE","—"); battery=metric(metrics,"BATTERY","—"); accuracy=metric(metrics,"LOCATION ACCURACY","Awaiting a fix")
        body.addView(metrics)
        val h=card(); h.addView(text("Recording health",17,ink,true)); health=text("Ready when you are.",14,muted).apply { setPadding(0,dp(8),0,0) }; h.addView(health); body.addView(h)
        val sensorCard=card(); sensorCard.addView(text("Sensor signals",17,ink,true)); sensors=text("Available sensors are discovered when recording begins.",13,muted).apply { setPadding(0,dp(10),0,0) }; sensorCard.addView(sensors); body.addView(sensorCard)
        body.addView(text("PRIVATE BY DEFAULT",11,green,true).apply { letterSpacing=.1f; gravity=Gravity.CENTER })
        body.addView(text("Saved on this device. Shared only when you export.\nNo account, maps, camera, or microphone.",12,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(6),0,dp(12)) })
        body.addView(button("Permissions & recording guide") { guide() })
        renderLive()
    }
    private fun metric(parent: LinearLayout,label: String,value: String): TextView {
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(8),0,dp(8)) }
        row.addView(text(label,10,muted,true).apply { letterSpacing=.04f },LinearLayout.LayoutParams(0,-2,1f))
        val v=text(value,16,ink,true); row.addView(v); parent.addView(row); return v
    }
    private fun renderLive() {
        if(screen!="record" || !::status.isInitialized) return
        val s=RecorderState.live
        val active=s.state=="recording"; val paused=s.state=="paused"; val preparing=s.state=="initializing"
        status.text="●  "+when { recovering -> "CHECKING SAVED SESSIONS"; preparing -> "PREPARING SENSORS"; active -> "RECORDING"; paused -> "PAUSED · SESSION SAVED"; else -> s.state.uppercase(Locale.US) }
        timer.text=formatDuration(s.durationNs)
        count.text=String.format(Locale.US,"%,d",s.observations); size.text=formatBytes(s.bytes)
        storage.text=formatBytes(if(s.available>0) s.available else filesDir.usableSpace)
        battery.text=if(s.battery<0) "—" else "${s.battery}%${if(s.charging) " · charging" else ""}"
        accuracy.text=s.accuracy?.let { "± ${String.format(Locale.US,"%.1f",it)} m" } ?: "Awaiting a fix"
        primary.text=when { preparing -> "Starting…"; active -> "Pause recording"; paused -> "Resume recording"; else -> "Start recording" }
        primary.isEnabled=!recovering && !preparing
        secondary.visibility=if(active || paused) View.VISIBLE else View.GONE
        health.text=if(s.warnings.isEmpty()) if(active) "Collecting from ${s.sources} sources. ${s.queued} observations awaiting storage." else if(paused) "Recording paused. Resume whenever you are ready." else "Ready when you are." else s.warnings.joinToString("\n\n")
        health.setTextColor(if(s.warnings.isEmpty()) muted else Color.rgb(150,79,25))
        sensors.text=if(s.sensorSummary.isEmpty()) "Hardware capabilities and delivered sampling rates appear here during recording." else s.sensorSummary.joinToString("\n\n")
    }
    private fun mainAction() {
        when(RecorderState.live.state) {
            "recording" -> command(RecordingService.PAUSE)
            "paused" -> command(RecordingService.RESUME)
            else -> prepareStart()
        }
    }
    private fun prepareStart() {
        AlertDialog.Builder(this).setTitle("Record your surroundings")
            .setMessage("Precise location captures GPS and radio positioning. Nearby devices enables Wi-Fi RTT and Bluetooth observations. Physical activity enables step sensors. Notifications keep recording visible.\n\nOnly supported, permitted sources will run. Recording uses battery and continues with the screen off. Data stays on this device until you export it.")
            .setNegativeButton("Cancel",null).setPositiveButton("Continue") { _,_ -> permissions() }.show()
    }
    private fun permissions() {
        val needed=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
        val sm=getSystemService(SensorManager::class.java)
        if(Build.VERSION.SDK_INT>=29 && (sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)!=null || sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)!=null)) needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if(Build.VERSION.SDK_INT>=31 && packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        if(Build.VERSION.SDK_INT>=33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
            if(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing=needed.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isEmpty()) startRecording() else requestPermissions(missing.toTypedArray(),100)
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==100) {
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) startRecording()
            else AlertDialog.Builder(this).setTitle("Location access is needed")
                .setMessage("Android requires location access for this foreground recording service. Enable location in app settings, then start again.")
                .setNegativeButton("Close",null).setPositiveButton("App settings") { _,_ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }.show()
        }
    }
    private fun startRecording() {
        if(RecorderState.live.state in listOf("recording","paused","initializing")) return
        RecorderState.live=LiveState(state="initializing")
        try { startForegroundService(Intent(this,RecordingService::class.java).setAction(RecordingService.START).putExtra("previous_session_id",previousId)); previousId=null }
        catch(e: Exception) { RecorderState.live=LiveState(warnings=listOf("Start failed: ${e.message}")) }
        screen="record"; buildScreen()
    }
    private fun command(action: String) { startService(Intent(this,RecordingService::class.java).setAction(action)) }
    private fun confirmStop() { AlertDialog.Builder(this).setTitle("Stop and save this session?").setMessage("Your observations will be committed to storage and ready to export.")
        .setNegativeButton("Keep recording",null).setPositiveButton("Stop and save") { _,_ -> command(RecordingService.STOP) }.show() }
    private fun guide() { AlertDialog.Builder(this).setTitle("Trustworthy field recording")
        .setMessage("Start while this app is visible. You can switch apps or turn the screen off; a recording notification provides Stop. Android, battery settings, or the device manufacturer can still interrupt recording.\n\nGPS may be unavailable indoors. Wi-Fi scanning is throttled by Android. Unfiltered Bluetooth scans may pause with the screen off. RTT requires compatible access points. Missing sources are reported.\n\nPausing stops acquisition. Interrupted sessions are recovered on next launch; Continue begins a linked new session. Stop before exporting.\n\nPrecise locations, Wi-Fi identifiers and BLE advertisements are sensitive. Choose an export destination you trust. No data is uploaded automatically.")
        .setPositiveButton("Got it",null).show() }
    private fun loadSessions() {
        body.removeAllViews(); body.addView(text("Loading saved sessions…",15,muted))
        io.execute { val result=SessionStore.list(RecorderState.root(this)); ui.post { sessionFiles=result; if(screen=="sessions") buildScreen() } }
    }
    private fun buildSessions() {
        body.addView(text("Your field notebook",23,ink,true))
        body.addView(text("Raw observations, ready for your processing engine.",14,muted).apply { setPadding(0,dp(8),0,dp(18)) })
        if(sessionFiles.isEmpty()) { val c=card(); c.addView(text("Your first walk starts here.",18,ink,true)); c.addView(text("Saved sessions will appear with their duration, size, and export options.",14,muted)); body.addView(c); return }
        val stopped=sessionFiles.filter { it.second.state !in listOf("recording","paused","initializing","damaged","metadata_unreadable") }.map { it.first }
        if(stopped.isNotEmpty()) body.addView(button(if(exporting) "Exporting…" else "Export all saved sessions") { chooseExport(stopped) }.apply { isEnabled=!exporting; layoutParams=LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(16) } })
        sessionFiles.forEach { (dir,m) ->
            val c=card()
            c.addView(text(if(m.startedWallMs==0L) "Date unavailable" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(m.startedWallMs)),17,ink,true))
            c.addView(text("${formatDuration(m.activeDurationNs)}   ·   ${formatBytes(m.bytes)}   ·   ${m.observationCount} observations",13,muted).apply { setPadding(0,dp(8),0,dp(6)) })
            c.addView(text(m.state.uppercase(Locale.US)+(if(m.synthetic) " · SYNTHETIC" else ""),11,if(m.state=="stopped") green else Color.rgb(150,79,25),true))
            if(m.state in listOf("interrupted","error","storage_full")) {
                c.addView(text("Recovered data is preserved. Continue starts a linked new session.",13,muted).apply { setPadding(0,dp(8),0,0) })
                c.addView(button("Continue recording") { previousId=m.id; prepareStart() }.apply { isEnabled=RecorderState.live.state !in listOf("recording","paused","initializing") })
            }
            val exportable=m.state !in listOf("recording","paused","initializing","damaged","metadata_unreadable")
            c.addView(button(if(exportable) "Export session" else if(m.state=="damaged") "Recovery needed — view details" else "Recording in progress") {
                if(exportable) chooseExport(listOf(dir)) else details(m)
            }.apply { isEnabled=!exporting; layoutParams=LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) } })
            c.addView(button("Session details") { details(m) }); body.addView(c)
            if(m.state in listOf("damaged","interrupted","error","storage_full")) c.addView(button("Export recovery files") { chooseExport(listOf(dir),true) }.apply { isEnabled=!exporting })
        }
    }
    private fun details(m: SessionMetadata) { AlertDialog.Builder(this).setTitle("Session details").setMessage("${m.id}\n\nStatus: ${m.state}\nObservations: ${m.observationCount}\nDropped by queue: ${m.droppedObservations}\nChunks: ${m.chunkCount}\n\n${m.notes.joinToString("\n")}")
        .setPositiveButton("Close",null).show() }
    private fun chooseExport(files: List<File>,recovery: Boolean=false) {
        if(exporting) return
        selectedExport=files
        recoveryExport=recovery
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE,if(recovery) "campus-recovery.zip" else ArchiveExporter.FILE_NAME),200)
    }
    @Deprecated("Platform document picker result") override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=200 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        val selected=selectedExport.toList(); exporting=true
        val recovery=recoveryExport
        if(screen=="sessions") buildScreen()
        io.execute {
            val result=runCatching {
                val destination=contentResolver.openOutputStream(uri,"wt") ?: error("Cannot open export destination")
                destination.use { output -> assets.open("schema/recording.proto").use { schema ->
                    if(recovery) RecoveryExporter.export(selected.single(),schema,output) else ArchiveExporter.export(selected,schema,output)
                } }
            }
            if(result.isFailure) runCatching { DocumentsContract.deleteDocument(contentResolver,uri) }
            ui.post {
                exporting=false
                AlertDialog.Builder(this).setTitle(if(result.isSuccess) "Export complete" else "Export failed")
                    .setMessage(if(result.isSuccess) "${selected.size} session(s) exported with schema and SHA-256 checksums." else "${result.exceptionOrNull()?.message}\nOriginal recordings remain on this device.")
                    .setPositiveButton("Close",null).show()
                if(screen=="sessions") buildScreen()
            }
        }
    }
    companion object {
        fun formatDuration(ns: Long): String { val seconds=ns/1_000_000_000; return String.format(Locale.US,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60) }
        fun formatBytes(bytes: Long): String=when { bytes<1024 -> "$bytes B"; bytes<1048576 -> String.format(Locale.US,"%.1f KiB",bytes/1024.0); bytes<1073741824 -> String.format(Locale.US,"%.1f MiB",bytes/1048576.0); else -> String.format(Locale.US,"%.1f GiB",bytes/1073741824.0) }
    }
}
