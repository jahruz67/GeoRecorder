# Sources, requested rates, permissions, and limitations

The app discovers capabilities at every start/resume and stores its full Android sensor inventory. A missing sensor or a rejected registration is a capability record, not a fabricated zero sample. Only one Android default sensor per useful type is activated; duplicate vendor streams and deprecated orientation are excluded. Distinct calibrated/uncalibrated physical streams and fusion algorithms are retained for their independent calibration/reference information.

| Source | Acquisition and preserved information |
| --- | --- |
| Accelerometer, gyroscope, magnetometer | Calibrated and uncalibrated types; raw float axes and bias estimates where supplied, raw accuracy, sensor timestamp. Requested period is max(2,500 µs, hardware minimum), up to 400 Hz. Delivered rates are measured. |
| Limited-axis inertial sensors | Calibrated and uncalibrated accelerometer/gyro types 38–41 when present; same rate policy, all axis-validity values preserved. |
| Gravity, linear acceleration | Separate platform estimates; linear acceleration uses the inertial rate policy; gravity requests up to 50 Hz. No app-side filtering. |
| Rotation, game rotation, geomagnetic rotation, heading | Distinct reference/fusion products, with all values and supplied uncertainty; continuous types request up to 50 Hz. Deprecated orientation is omitted. |
| Pose 6DoF, head tracker, hinge angle | Useful optional orientation/device-configuration evidence; raw value arrays and accuracy, up to 50 Hz where continuous. Many phones do not expose them. |
| Barometer | Raw pressure in hPa, up to 50 Hz if supported. First-session reference and explicitly derived pressure difference are secondary fields. Weather, temperature, motion and drift remain in the raw data; there is no absolute floor estimate. |
| Ambient temperature | Optional environmental context for barometric interpretation, up to 50 Hz where continuous. Many devices do not expose a true ambient-temperature sensor. |
| Step detector and counter | Event-driven detector/counter; requires physical activity permission on API 29+. The counter is cumulative since boot, not automatically reset by this app. Values remain raw. |
| GPS location | Requested every 200 ms with zero displacement threshold. Android/hardware can provide a slower rate. WGS84 coordinates, ellipsoid altitude, optional MSL altitude, bearing, speed, uncertainties, mock/provider flags, and elapsed timestamps are copied. |
| Passive and network locations | Additional provider observations when exposed; requested every 1 s. An offline device may provide none. Provider identity is retained; these are never conflated with GNSS fixes. No Play Services dependency. |
| Raw GNSS | Public measurement callback, full tracking on API 31+, default fastest measurement interval. Receiver clock, discontinuities, per-satellite code/carrier measurements and flags, C/N0, frequency, ADR, uncertainties, optional bias/PVT/AGC and publicly exposed nested data. No pseudorange/floor/path solution is computed. |
| Satellite status | Constellation/SVID, elevation/azimuth, C/N0, optional baseband C/N0/frequency, used-in-fix, ephemeris/almanac flags. Callback timing is retained. |
| GNSS navigation and NMEA | Raw navigation bytes/type/status/IDs and complete NMEA sentences/supplied wall times. Callback registration can succeed on a device that never emits these streams; health and capability status do not promise availability. |
| Wi-Fi scan | Active scan requested every 35 s, plus passive scan-result broadcasts and cached snapshots. Every returned AP is retained, including repeated/cached scans. BSSID, SSID, RSSI, frequency/channel data, hardware scan timestamp, capability strings, RTT responder flag, available standard and information-element payloads. |
| BLE | Unfiltered low-latency scan with no batching. Address, RSSI, raw advertisement, transmit-power sentinel, PHY/SID/connectability/legacy/data-status, hardware time. Extended/legacy advertisements and all supported PHYs are requested when supported; an unsupported-feature error triggers an explicit legacy fallback. No device connection is made. |
| Wi-Fi RTT | When hardware and APs support 802.11mc, requests are attempted approximately every 2 s while prior ranging is complete. API peer limits are respected; responder subsets rotate if there are more peers than one request permits. Raw successful results, uncertainty/RSSI/sample counts/timestamps and failure codes are retained. |

These rates are requests, not guarantees. Source statistics report actual nonnegative hardware timestamp intervals, minimum/maximum gap, regressions, event count, and queue drops. Intentional pause gaps are excluded from rate estimates. On-change sensors report changes and cannot be interpreted as periodic sampling. A long gap can be a real stationary/on-change interval or a platform interruption, and does not identify an exact number of hardware drops.

## Permissions

| Permission | Purpose and behavior if missing |
| --- | --- |
| Coarse/fine location | A location grant is required to start the location foreground service. Precise location is needed for raw GNSS and Wi-Fi observations. With approximate-only access, available location/inertial sources run and restrictions are recorded. |
| Physical activity | Step sensors on API 29+; omitted when the device has no step sensor. Other recording continues if denied. |
| Nearby Bluetooth devices (`BLUETOOTH_SCAN`) | BLE scanning on API 31+. Pre-31 uses legacy Bluetooth permissions and precise location. The manifest does not assert `neverForLocation`, because observations are intended for positioning. No BLUETOOTH_CONNECT permission is requested. |
| Nearby Wi-Fi devices | RTT on API 33+ when RTT hardware is advertised. Precise location remains declared for location-derived scanning. No `neverForLocation` assertion is made. |
| Notifications | Visible recording status on API 33+. Denial does not grant or bypass Android's foreground-service rules; Android may expose service status only in its active-apps UI. |
| Foreground service/location | Required compliant ongoing location service; initiated while the activity is visible. No background-location permission or boot auto-start is used. |
| High sampling rate sensors | Requests inertial rates over Android's usual 200 Hz cap. Platform privacy settings, microphone privacy switches, or device policy can still cap delivery. |
| Wake lock | A bounded partial wake lock while actively recording; supports non-wakeup sensor delivery during screen off. Released on pause/stop/error. |
| Wi-Fi state/change state | Read scan results and request platform-throttled scans. The app does not toggle Wi-Fi, Bluetooth, or location settings. |

## Unavoidable limits and explicit exclusions

- Android can terminate a process, revoke permissions, stop a foreground app, apply Doze/thermal restrictions, or impose manufacturer battery policies. The service does not restart without a new user action. Screen-off continuity must be qualified on each target device.
- Wi-Fi foreground scans are generally limited to four requests per two minutes, with stricter background limits. The 35-second request period stays below the foreground limit; background/other-app requests and vendor policies can still cause rejection. Hardware scan timestamps expose staleness. Throttling is never disabled programmatically.
- Android documents that unfiltered BLE scans can stop with screen off. No universal location scanner can force all advertisements through this restriction. Devices that only support legacy scanning cannot supply extended-only advertisements. There is no privileged radio sniffer, channel-state information, ranging to arbitrary BLE devices, or guaranteed beacon identity across rotating addresses.
- RTT requires a supported device, enabled system services, a location/nearby grant, and compatible reachable APs. Android can throttle or reject ranging, especially in the background. 802.11az-specific setup is not implemented; the public 802.11mc AP path is implemented.
- Raw GNSS capability and actual emissions vary by chipset. The public SDK does not expose the privileged request that enables correlation vector output; it is not invoked through hidden APIs. Public getter values are retained if the platform supplies them. Navigation bits, full tracking, optional PVT/AGC, and continuous carrier measurements may be absent even when callback registration succeeds.
- No magnetometer hard/soft-iron calibration, drift correction, GNSS smoothing, floor inference, trajectory reconstruction, path deduplication, map snapping, or coordinate enhancement occurs. Android's own calibrated/fused outputs are explicitly separate sources.
- Dynamic hotplug/vendor sensor selection, direct sensor channels, cellular tower/telephony acquisition, light/proximity streams, cameras, microphones, and privileged/debug GNSS interfaces are not enabled. No independent positioning claim is made for excluded trigger or redundant orientation streams.
- No raw hardware loss counter is exposed by the normal Android sensor callback API. The recorder measures its own queue losses and reports gaps/regressions and stale streams without claiming exact upstream loss.

Primary references: [Android sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview), [GNSS measurements](https://developer.android.com/reference/android/location/GnssMeasurement), [GNSS clock](https://developer.android.com/reference/android/location/GnssClock), [GNSS requests](https://developer.android.com/reference/android/location/GnssMeasurementRequest.Builder), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [BLE scanner](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner), and [Wi-Fi RTT](https://developer.android.com/develop/connectivity/wifi/wifi-rtt). Device behavior should be verified against its Android version and vendor implementation.
