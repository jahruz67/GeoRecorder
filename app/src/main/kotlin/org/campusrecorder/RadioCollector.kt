package org.campusrecorder

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.*
import android.os.Build
import android.os.Handler
import org.campusrecorder.core.*
import org.campusrecorder.proto.Recording.*
import java.util.concurrent.Executor

class RadioCollector(context: Context,handler: Handler,emit: (Observation)->Unit) : Collector(context,handler,emit) {
    private val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
    private val rtt=context.getSystemService(WifiRttManager::class.java)
    private var scanner: BluetoothLeScanner?=null
    private var running=false
    private var receiverRegistered=false
    private var rttPending=false
    private var lastRttNs=0L
    private var peers=emptyList<android.net.wifi.ScanResult>()
    private var lastRttMessage: String?=null
    private var rttPeerOffset=0
    private var extendedBle=false
    private val executor=Executor { handler.post(it) }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context,i: Intent) {
            if(running) readWifi(i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false))
        }
    }
    private val scan: ScanCallback=object: ScanCallback() {
        override fun onScanResult(callbackType: Int,result: ScanResult) { handler.post { if(running) ble(callbackType,result) } }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { handler.post { if(running) results.forEach { ble(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,it) } } }
        override fun onScanFailed(errorCode: Int) { handler.post {
            status("ble","Scan failed code=$errorCode")
            if(running && extendedBle && errorCode==ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                extendedBle=false; status("ble","Extended scan rejected; retrying legacy advertisements"); startBle()
            }
        } }
    }
    private fun ble(type: Int,r: ScanResult) = attempt("ble") {
        event(Kind.BLE_SCAN,"ble",r.timestampNanos,fields("address" to r.device.address,"rssi_dbm" to r.rssi,
            "callback_type" to type,"advertisement" to r.scanRecord?.bytes,"tx_power_dbm" to r.txPower,
            "connectable" to r.isConnectable,"legacy" to r.isLegacy,"primary_phy" to r.primaryPhy,
            "secondary_phy" to r.secondaryPhy,"advertising_sid" to r.advertisingSid,
            "periodic_advertising_interval" to r.periodicAdvertisingInterval,"data_status" to r.dataStatus,
            "timestamp_domain" to "scan_result_elapsed_realtime_ns"))
    }
    private val wifiTick=object: Runnable {
        override fun run() {
            if(!running) return
            attempt("wifi") {
                if(permitted(Manifest.permission.ACCESS_FINE_LOCATION) && wifi!=null) {
                    val requested=wifi.startScan()
                    event(Kind.CAPABILITY,"wifi",properties=fields("scan_request_accepted" to requested,"note" to "Android throttles active scans; cached/repeated observations are preserved"))
                    // Cached snapshots are useful even if an active scan was throttled. Hardware times expose staleness.
                    readWifi(false)
                } else status("wifi","Precise location permission or Wi-Fi hardware unavailable")
            }
            handler.postDelayed(this,35000)
        }
    }
    private val rttTick=object: Runnable {
        override fun run() {
            if(!running) return
            requestRtt(peers)
            handler.postDelayed(this,2000)
        }
    }
    private fun rttStatus(message: String) { if(message!=lastRttMessage) { lastRttMessage=message; status("wifi:rtt",message) } }
    // Permission helper and attempt boundary handle both initial denial and mid-call revocation.
    @SuppressLint("MissingPermission")
    override fun start() {
        running=true
        if(wifi!=null) {
            attempt("wifi") {
                val filter=IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                // Protected system scan broadcast can come from the privileged Wi-Fi UID.
                if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,null,handler,Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver,filter,null,handler)
                receiverRegistered=true; handler.post(wifiTick)
            }
        } else status("wifi","Wi-Fi service unavailable")
        status("wifi:rtt","hardware=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)} available=${rtt?.isAvailable==true}",rtt?.isAvailable==true)
        if(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) handler.post(rttTick)
        if(Build.VERSION.SDK_INT>=31 && !permitted(Manifest.permission.BLUETOOTH_SCAN)) { status("ble","Nearby Bluetooth scan permission denied"); return }
        if(Build.VERSION.SDK_INT<31 && !permitted(Manifest.permission.ACCESS_FINE_LOCATION)) { status("ble","Precise location permission denied"); return }
        attempt("ble") {
            // getBluetoothLeScanner is permission-free; BLUETOOTH_CONNECT is deliberately not requested.
            val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
            scanner=adapter?.bluetoothLeScanner
            if(scanner==null) { status("ble","BLE unsupported or Bluetooth disabled"); return@attempt }
            extendedBle=adapter?.isLeExtendedAdvertisingSupported==true
            event(Kind.CAPABILITY,"ble",properties=fields("extended_advertising_supported" to extendedBle,
                "coded_phy_supported" to adapter?.isLeCodedPhySupported,"offloaded_filtering_supported" to adapter?.isOffloadedFilteringSupported))
            startBle()
        }
    }
    @SuppressLint("MissingPermission")
    private fun startBle(): Unit=attempt("ble") {
        if(Build.VERSION.SDK_INT>=31 && !permitted(Manifest.permission.BLUETOOTH_SCAN)) { status("ble","Bluetooth scan permission denied"); return@attempt }
        val settings=ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).setLegacy(!extendedBle)
        if(extendedBle) settings.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        scanner?.startScan(null,settings.build(),scan)
        status("ble","Low latency BLE scan started; extended=$extendedBle; Android can suspend unfiltered scans with screen off",true)
    }
    @SuppressLint("MissingPermission")
    private fun readWifi(updated: Boolean) = attempt("wifi") {
        if(!permitted(Manifest.permission.ACCESS_FINE_LOCATION)) { status("wifi","Precise location permission denied"); return@attempt }
        val results=wifi?.scanResults ?: emptyList()
        event(Kind.WIFI_SCAN,"wifi",properties=fields("results_updated" to updated,"result_count" to results.size),groups=results.map { r ->
            group("access_point","bssid" to r.BSSID,"ssid" to r.SSID,"rssi_dbm" to r.level,"frequency_mhz" to r.frequency,
                "timestamp_us" to r.timestamp,"timestamp_domain" to "microseconds_since_boot",
                "channel_width" to r.channelWidth,"center_frequency0_mhz" to r.centerFreq0,"center_frequency1_mhz" to r.centerFreq1,
                "capabilities" to r.capabilities,"is_80211mc_responder" to r.is80211mcResponder,
                "wifi_standard" to if(Build.VERSION.SDK_INT>=30) r.wifiStandard else null,
                "information_elements_hex" to if(Build.VERSION.SDK_INT>=30) r.informationElements.joinToString(";") { ie ->
                    val b=ie.bytes.duplicate(); val bytes=ByteArray(b.remaining()); b.get(bytes)
                    "${ie.id}/${ie.idExt}:"+bytes.joinToString("") { "%02x".format(it) }
                } else null)
        })
        peers=results; requestRtt(results)
    }
    @SuppressLint("MissingPermission")
    private fun requestRtt(results: List<android.net.wifi.ScanResult>) = attempt("wifi:rtt") {
        if(rttPending || !running || rtt?.isAvailable!=true) return@attempt
        if(!permitted(Manifest.permission.ACCESS_FINE_LOCATION)) { rttStatus("Precise location permission denied"); return@attempt }
        if(Build.VERSION.SDK_INT>=33 && !permitted(Manifest.permission.NEARBY_WIFI_DEVICES)) { rttStatus("Nearby Wi-Fi permission denied"); return@attempt }
        val now=android.os.SystemClock.elapsedRealtimeNanos()
        if(now-lastRttNs<1_900_000_000L) return@attempt
        val allResponders=results.filter { it.is80211mcResponder }
        if(allResponders.isEmpty()) { rttStatus("No 802.11mc responder APs in scan"); return@attempt }
        val responders=(0 until minOf(allResponders.size,RangingRequest.getMaxPeers())).map { allResponders[(rttPeerOffset+it)%allResponders.size] }
        rttPeerOffset=(rttPeerOffset+responders.size)%allResponders.size
        rttPending=true; lastRttNs=now
        try {
            rtt.startRanging(RangingRequest.Builder().addAccessPoints(responders).build(),executor,object: RangingResultCallback() {
                override fun onRangingFailure(code: Int) { rttPending=false; if(running) event(Kind.WIFI_RTT,"wifi:rtt",properties=fields("request_failure_code" to code)) }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rttPending=false
                    if(!running) return
                    event(Kind.WIFI_RTT,"wifi:rtt",groups=results.map { r ->
                        if(r.status==RangingResult.STATUS_SUCCESS) {
                            val snapshot=AndroidSnapshot.read(r)
                            Group.newBuilder().setName("ranging_result").addAllFields(snapshot.fields+fields("mac_address" to r.macAddress?.toString(),
                                "timestamp_domain" to "getRangingTimestampMillis: milliseconds_since_boot")).build()
                        } else group("ranging_result","status" to r.status,"mac_address" to r.macAddress?.toString())
                    })
                }
            })
        } catch(e: Exception) { rttPending=false; throw e }
    }
    @SuppressLint("MissingPermission")
    override fun stop() {
        running=false; handler.removeCallbacks(wifiTick); handler.removeCallbacks(rttTick)
        rttPending=false
        if(receiverRegistered) { runCatching { context.unregisterReceiver(receiver) }; receiverRegistered=false }
        scanner?.let { runCatching { it.stopScan(scan) } }; scanner=null
    }
}
