package org.campusrecorder

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.location.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import org.campusrecorder.core.*
import org.campusrecorder.proto.Recording.*
import java.util.concurrent.Executor

class GnssCollector(context: Context,handler: Handler,emit: (Observation)->Unit,
    private val accuracy: (Float?)->Unit) : Collector(context,handler,emit) {
    private val manager=context.getSystemService(LocationManager::class.java)
    private val executor=Executor { handler.post(it) }
    private val locations=object: LocationListener {
        override fun onLocationChanged(location: Location) {
            accuracy(if(location.hasAccuracy()) location.accuracy else null)
            val s=AndroidSnapshot.read(location)
            event(Kind.LOCATION,"location:${location.provider}",location.elapsedRealtimeNanos,
                s.fields+fields("coordinate_reference" to "EPSG:4326","altitude_reference" to "WGS84 ellipsoid",
                    "msl_altitude_reference" to if(Build.VERSION.SDK_INT>=34 && location.hasMslAltitude()) "mean sea level (Android geoid model)" else null,
                    "timestamp_domain" to "elapsedRealtimeNanos"),s.groups)
        }
        override fun onProviderEnabled(provider: String) { status("location:$provider","Provider enabled",true) }
        override fun onProviderDisabled(provider: String) { status("location:$provider","Provider disabled"); accuracy(null) }
        @Deprecated("Legacy platform callback") override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?) {
            event(Kind.CAPABILITY,"location:$provider",properties=fields("provider_status" to status))
        }
    }
    private val measurements=object: GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(e: GnssMeasurementsEvent) {
            val snapshot=AndroidSnapshot.read(e,ignoreGetters=setOf("getClock"))
            val clock=AndroidSnapshot.read(e.clock,"clock/")
            val hardwareTime=if(Build.VERSION.SDK_INT>=29 && e.clock.hasElapsedRealtimeNanos()) e.clock.elapsedRealtimeNanos else null
            event(Kind.GNSS_RAW,"gnss:raw",hardwareTime ?: SystemClock.elapsedRealtimeNanos(),
                snapshot.fields+fields("timestamp_domain" to if(hardwareTime!=null) "gnss_clock_elapsed_realtime_ns" else "callback_elapsed_realtime_ns"),
                listOf(Group.newBuilder().setName("clock").addAllFields(clock.fields).build())+clock.groups+snapshot.groups)
        }
        @Deprecated("Framework status") override fun onStatusChanged(status: Int) { event(Kind.CAPABILITY,"gnss:raw",properties=fields("measurement_status" to status)) }
    }
    private val satellites=object: GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            event(Kind.GNSS_SATELLITES,"gnss:satellites",groups=(0 until s.satelliteCount).map { i -> group("satellite",
                "svid" to s.getSvid(i),"constellation" to s.getConstellationType(i),"cn0_dbhz" to s.getCn0DbHz(i),
                "azimuth_deg" to s.getAzimuthDegrees(i),"elevation_deg" to s.getElevationDegrees(i),
                "used_in_fix" to s.usedInFix(i),"has_almanac" to s.hasAlmanacData(i),"has_ephemeris" to s.hasEphemerisData(i),
                "carrier_frequency_hz" to if(s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i) else null,
                "baseband_cn0_dbhz" to if(Build.VERSION.SDK_INT>=30 && s.hasBasebandCn0DbHz(i)) s.getBasebandCn0DbHz(i) else null) })
        }
        override fun onStarted() { status("gnss:receiver","GNSS started",true) }
        override fun onStopped() { status("gnss:receiver","GNSS stopped") }
        override fun onFirstFix(ttffMillis: Int) { event(Kind.CAPABILITY,"gnss:receiver",properties=fields("time_to_first_fix_ms" to ttffMillis)) }
    }
    private val navigation=object: GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(message: GnssNavigationMessage) {
            val s=AndroidSnapshot.read(message)
            event(Kind.GNSS_NAVIGATION,"gnss:navigation",properties=s.fields,groups=s.groups)
        }
        override fun onStatusChanged(status: Int) { event(Kind.CAPABILITY,"gnss:navigation",properties=fields("navigation_status" to status)) }
    }
    private val nmea=OnNmeaMessageListener { message,timestamp ->
        event(Kind.GNSS_NMEA,"gnss:nmea",properties=fields("sentence" to message,"nmea_wall_ms" to timestamp,"timestamp_domain" to "callback_elapsed_realtime_ns"))
    }
    // Runtime checks use Collector.permitted; every registration also catches revocation via attempt.
    @SuppressLint("MissingPermission")
    override fun start() {
        if(manager==null) { status("gnss","Location service unavailable"); return }
        if(!permitted(Manifest.permission.ACCESS_COARSE_LOCATION)) { status("gnss","Location permission denied"); return }
        event(Kind.CAPABILITY,"gnss",properties=fields("hardware_model" to manager.gnssHardwareModelName,"hardware_year" to manager.gnssYearOfHardware,
            "location_enabled" to manager.isLocationEnabled,"fine_permission" to permitted(Manifest.permission.ACCESS_FINE_LOCATION)))
        if(Build.VERSION.SDK_INT>=30) {
            attempt("gnss:capabilities") {
                val capabilities: GnssCapabilities?=manager.gnssCapabilities
                if(capabilities!=null) {
                    val c=AndroidSnapshot.read(capabilities)
                    event(Kind.CAPABILITY,"gnss:capabilities",properties=c.fields,groups=c.groups)
                } else status("gnss:capabilities","GNSS capabilities unavailable from framework")
            }
        }
        for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.PASSIVE_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
            if(provider !in manager.allProviders) { status("location:$provider","Provider unavailable"); continue }
            attempt("location:$provider") {
                manager.requestLocationUpdates(provider,if(provider==LocationManager.GPS_PROVIDER) 200L else 1000L,0f,locations,handler.looper)
                status("location:$provider","Location updates requested; may yield no fixes offline/indoors",true)
            }
        }
        if(!permitted(Manifest.permission.ACCESS_FINE_LOCATION)) { status("gnss:raw","Precise location denied; raw GNSS unavailable"); return }
        attempt("gnss:raw") {
            val ok=if(Build.VERSION.SDK_INT>=31) manager.registerGnssMeasurementsCallback(
                GnssMeasurementRequest.Builder().setFullTracking(true).build(),executor,measurements)
            else manager.registerGnssMeasurementsCallback(measurements,handler)
            status("gnss:raw","Raw measurement callback registration=$ok; full tracking requested on API 31+",ok)
            status("gnss:correlation","Correlation vector request is not a public Android SDK API; public values are preserved if exposed by the receiver")
        }
        attempt("gnss:satellites") { val ok=manager.registerGnssStatusCallback(satellites,handler); status("gnss:satellites","Satellite callback registration=$ok",ok) }
        attempt("gnss:navigation") { val ok=manager.registerGnssNavigationMessageCallback(navigation,handler); status("gnss:navigation","Navigation callback registration=$ok",ok) }
        attempt("gnss:nmea") { val ok=manager.addNmeaListener(nmea,handler); status("gnss:nmea","NMEA callback registration=$ok",ok) }
    }
    override fun stop() {
        manager?.let { m ->
            runCatching { m.removeUpdates(locations) }; runCatching { m.unregisterGnssMeasurementsCallback(measurements) }
            runCatching { m.unregisterGnssStatusCallback(satellites) }; runCatching { m.unregisterGnssNavigationMessageCallback(navigation) }; runCatching { m.removeNmeaListener(nmea) }
        }
    }
}
