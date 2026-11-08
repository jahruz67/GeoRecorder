# Verification and field qualification

This report distinguishes host verification from physical-device evidence. No physical phone was attached to the build host. The Android emulator had no hypervisor driver; two headless software-emulation attempts terminated with native access violation `0xC0000005` before a device appeared in ADB. Device instrumentation execution, screen-off hardware continuity, physical GNSS/radio quality, and battery impact are **not verified** here. The test APK is compiled and available for execution on a suitable device.

## Automated host checks

The delivery includes machine-readable JUnit XML, HTML reports, Python unittest output, the full successful Gradle build log, Android lint reports, decoder validation summary, signing verification, and the synthetic benchmark JSON under `deliverables/test-results/`.

| Check | Coverage |
| --- | --- |
| Kotlin core tests | Timestamp regressions preserved; integer nanosecond precision; delivery rates and pause-gap exclusion; invalid sequence rejection; pause/resume queue drain; deterministic overload accounting; simulated low storage without changing earlier chunks; interrupted/partial journal salvage; publish/unlink crash deduplication; metadata reconstruction; corrupt immutable chunk forensic preservation; export/reimport hashes; corrupted gzip and missing trailer; refusing active/damaged validated exports; logical-hour multi-chunk recording; missing-source capability records. |
| Android host tests | Six behaviors each on API 28 and 36 using Robolectric: unavailable hardware discovery; public Location getter timestamps/optional values; denied radio permissions; activity controls and permission explanation; hardware float/timestamp/accuracy copying with no smoothing; foreground service start/pause/resume/stop and durable accounting. These are framework simulations, not phone measurements. |
| Python reference reader tests | Decode every observation and all fourteen kinds in the example; signed integer/double/packed float compatibility; unknown protobuf fields; corrupt gzip even after recomputed archive hash; tampered metadata; missing chunks; unchecked members; malformed wire payload; incorrect manifest count. |
| Android build/lint | Debug APK, unsigned release APK, instrumentation APK, debug lint, and release vital lint compile/check successfully. Remaining lint warnings are documented below. |
| Signed artifact validation | Release APK is zip-aligned and verified by Android `apksigner`; public certificate is delivered. Manifest inspection confirms min API 28, target API 36, no INTERNET/camera/microphone permissions and no debugging flag. |

The final machine-readable result counts are authoritative. A successful final run has 16 core cases, 12 Android host cases, and 9 Python cases: **37 passing host tests**. The five device instrumentation cases are compiled, **not counted as passed**.

## Synthetic benchmark

Run `:core:synthetic` to produce a ten-minute logical 400 Hz stream of 240,000 accelerometer observations, plus a second session containing fourteen schema fixtures, then export both through the production core. Synthetic records and metadata are explicitly marked. The normal Android service always uses real Android collectors.

`deliverables/synthetic-benchmark.json` reports wall processing time, sustained observations/s, compressed bytes/s, output size and bytes/observation, sampled JVM heap use and exact queue drops. It uses a paced producer to remain below the acquisition queue bound; a separate overload test intentionally forces losses and checks accounting. The benchmark includes writing, durable checkpoints and pause/resume/final commit for the 240,000-record stream. Archive export and the additional fixture session are outside the timed interval. Host filesystem caches, Java GC, antivirus and concurrent Gradle compilation affect the result; it is not a phone performance prediction.

The logical-hour test writes 360,000 100 Hz timestamped observations over more than 100 chunks and validates every sequence/count. It runs faster than wall time and does not establish a real one-hour screen-off battery result. Memory reports are sampled used JVM heap, not total process RSS or Android RAM consumption. No desktop benchmark can measure the phone's battery drain.

## Remaining lint warnings

HighSamplingRate is intentional: the application requests useful inertial rates over 200 Hz. UsableSpace recommends potential cache reclamation; this recorder conservatively uses physically free bytes and a 32 MiB reserve, without reclaiming other apps' caches. SetTextI18n flags English-only programmatic interface labels. These warnings are retained in the report, not hidden with a baseline. MissingPermission suppressions are localized to collector methods whose permission helper and exception boundary handle initial denial and mid-call revocation; all newer foreground/inset APIs are version-guarded.

## Physical-device protocol

Run `./gradlew :app:connectedDebugAndroidTest` on an authorized test device. The compiled five-case suite checks sensor discovery, framework timestamp preservation, private disk/export/pause behavior, permission-denied radio handling, and a foreground recording through screen off, pause, resume and stop with source samples and count reconciliation. Then perform this qualification protocol on every intended phone model:

1. **Permissions and absent hardware:** start with precise location denied, then with approximate-only permission, denied step/BLE/nearby/notification access, disabled system location/Wi-Fi/Bluetooth, and unavailable RTT/barometer. Confirm useful remaining streams run, errors/capabilities are recorded, and denied sources do not fabricate values. Grant permission, pause/resume or start a new session to rediscover.
2. **Two-hour sustained walk/idle:** record outdoors, indoors and through floor changes, including stationary periods and repeated paths. Leave the app backgrounded and screen off for at least 30 minutes. Check notification stop and launcher re-entry. Validate timestamps, delivered rates, gaps, count accounting and storage growth with the decoder. Check that no map or network is needed.
3. **Power and thermal baseline:** run matched idle and recording intervals from comparable battery state/temperature, unplugged, with identical screen/radio settings. Read exported health current/energy/charge counters only where the device supports them. Record wall duration, battery percentage change, charge/energy change and thermal status; repeat. Do not attribute whole-device current exclusively to the recorder. Use `adb shell dumpsys batterystats` and `dumpsys meminfo org.campusrecorder` as complementary measurements on a test build/device.
4. **Pause/resume:** pause for several minutes, then resume from the visible activity. Confirm active duration excludes the pause, sensor samples stop, health/clock status is explicitly distinguishable, and rate estimates do not include the deliberate gap.
5. **Actual process interruption:** on a sacrificial test session, run `adb shell am force-stop org.campusrecorder` while recording. Relaunch manually. Confirm the service did not silently auto-start, the session is interrupted/recovered, complete journal frames are retained, the original journal is locally preserved, and Continue starts a linked new session. Export and validate recovered data. Repeat near a checkpoint boundary.
6. **Storage exhaustion:** use a dedicated test phone/emulator volume with controlled free space. Confirm warning below 128 MiB, stop near the 32 MiB reserve, a visible error, and unchanged hashes for older sessions. Do not fill the user's primary phone to test this.
7. **GNSS/radios:** outdoor open-sky logging on a raw-GNSS capable chipset, indoor loss of fixes, dual-frequency observations if supported, known BLE advertisers, and known 802.11mc RTT APs. Compare available flags, timestamps and uncertainties against the hardware. Registration success alone is not proof that data was delivered.
8. **Export/integrity:** export a large multi-session archive to a local document provider. Decode to JSONL, validate SHA-256, counts and sequence order, and repeat with a deliberately modified copy to ensure rejection. For a sacrificial damaged session verify the separate recovery bundle preserves original bytes.

Record device model/build, Android version, battery optimization settings, duration, all observed failures, and which optional hardware is absent alongside the results. Unverified field behavior remains an explicit delivery limitation.
