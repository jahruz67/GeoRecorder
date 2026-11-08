# Campus Recorder — current status and handoff

**Updated:** 2026-11-08  
**Workspace:** `C:\Users\360he\Documents\ChatGPT\datarecod`  
**Status:** The Android application, recording/export core, decoder, documentation, signed sideload APK, synthetic example archive, and host verification are implemented. The remaining qualification work requires a physical Android device: execute the compiled device tests, verify real screen-off collection and receiver behavior, measure phone power/storage use, and update the field-test report. This Windows host could not boot its software emulator.

## Goal and scope

Build **Campus Recorder**, an offline-first native Kotlin Android data collector for later campus pedestrian-map reconstruction. A user starts a recording and walks anywhere, including indoors, between floors, and over paths they have recorded before. There is no route, destination, campus boundary, account, map, or manual classification step. Preserve raw observations for a separate downstream reconstruction project.

The recorder must discover supported hardware; gather useful sensor, location, raw GNSS, Wi-Fi, BLE, and Wi-Fi RTT observations where permitted; preserve source and monotonic timestamps, optionality, units, uncertainty and hardware flags; operate through an explicit, visible foreground service; report actual delivery and its own queue drops; survive ordinary interruptions with recoverable on-device append files; and export versioned, documented, integrity-checked archives. Location and radio records are sensitive and stay on-device until the user explicitly chooses an export destination.

**Do not describe the synthetic fixture as a real field recording.** Real sensor collection is implemented through Android framework callbacks. The checked-in example exercises the real core and file format with clearly identified synthetic observations. No genuine phone walk has been recorded in this workspace.

## Implemented

- The two-module native Android project is `app/` plus the platform-independent Kotlin/JVM `core/`. Gradle wrapper, pinned dependencies, generated protobuf lite messages, API 36 compilation and a self-contained Python decoder are present. Minimum Android API is 28.
- The recording UI has Record/Sessions screens, Start/Pause/Resume/Stop, elapsed active time, observations, size, storage, battery, location accuracy, warnings, sensor capability/delivery status, recovery/continue, session detail, privacy and permission explanations, and Storage Access Framework export.
- An explicitly started `location` foreground service owns a high-priority acquisition handler, bounded partial wake lock, visible Stop notification, periodic clock anchors and health records. It does not auto-start on boot, and uses `START_NOT_STICKY`. The handler receives platform collectors; one background writer owns disk.
- Motion collection discovers the full Android sensor inventory and requests default accelerometer, uncalibrated accelerometer, gyroscope, uncalibrated gyroscope, magnetometer, uncalibrated magnetometer, limited-axis inertial sensors, gravity, linear acceleration, distinct rotation estimates, pressure, step detector/counter, pose, hinge, heading, head-tracker, and ambient-temperature types when available. Registration, hardware limits, requested periods, accuracy, raw arrays and unavailable sources are reported. No smoothing or fusion is performed in application code. Pressure is explicitly relative, not a floor elevation.
- Location collection registers GPS, network and passive providers. Public raw GNSS measurement, clock, satellite, capability, navigation-message and NMEA callbacks preserve available hardware fields, flags, receiver timestamps, and uncertainties. It does not claim that callback registration guarantees that hardware delivers data. The public Android SDK does not expose the privileged correlation-vector-output request; no hidden API is used.
- Wi-Fi observations preserve each returned/cached AP and original scan timestamp; a foreground scan is requested every 35 seconds, subject to Android throttling. BLE uses an unfiltered low-latency scan, requests extended advertising/all PHYs when supported, falls back to legacy where necessary, and records permissions/capabilities. RTT requests rotate through reachable 802.11mc responders when necessary and preserve raw success/failure fields. No radio identifier is removed or deduplicated.
- Protobuf framing and append-oriented per-session `.open` journals are implemented with a bounded nonblocking 16,384-record/16 MiB queue, exact application-queue drop accounting, monotonic arrival sequence, hardware timestamps, two-second durable checkpoints, approximately 1 MiB independently gzipped chunks, fsync, atomic publication and atomic metadata replacement. Storage exhaustion stops recording and leaves old sessions intact.
- Startup recovery salvages complete frames, retains unfinished journals and corrupted metadata/chunks for inspection, recounts valid durable observations, and marks interruption/damage. Continuing an interrupted recording starts a new linked session because elapsed realtime may reset. A validated export refuses damaged/open sessions; a separately named recovery archive preserves original bytes for forensic recovery.
- The versioned schema is [schema/recording.proto](schema/recording.proto). Streaming export writes `manifest.json`, schema, session metadata, independently readable gzip protobuf chunks and `checksums.sha256` in `campus-recordings-v1.zip`. The standard-library Python decoder verifies ZIP paths/members/checksums, gzip CRC, frame bounds, protobuf framing, counts and sequence order, and can stream typed JSONL.
- Private app storage, no `INTERNET`/account/camera/microphone permission, no automatic cloud upload, and no backup/device transfer are configured. The user chooses the export provider and destination.
- The user’s technical and operational requirements, file-format semantics, sensor availability/Android restrictions, build steps, test findings, and a physical-device protocol are documented in `docs/` and the project README.

## Current verification results

**Passing host checks (37 tests):**

- 16 Kotlin core tests: framing, precision, ordering/rate metrics, pause/resume, overload accounting, storage exhaustion preserving history, interrupted journals, publication/deletion crash case, metadata repair, corrupt-byte forensic retention, export/checksums, invalid chunks, unavailable capabilities, and a 360,000-observation logical-hour chunk test.
- 12 Robolectric Android tests across API 28 and 36: sensor absence and registration behavior, `Location` precision/optional getters, radio permission denial, app/permission UI, verbatim raw sensor arrays/timestamps/accuracy, and service start/pause/resume/stop/durable-accounting behavior. These simulate Android and are not a substitute for a phone.
- 9 Python standard-library tests: read all observations/all 14 observation types from the sample, wire-type compatibility/unknown fields, and rejection of altered hashes, corrupt gzip, missing chunks, malformed frames and archive/manifest inconsistencies.
- Final full Gradle command passed: `:core:test :core:synthetic :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug`.
- The signed release APK passed `apksigner verify` and `zipalign -c`. APK inspection confirms `org.campusrecorder`, version 1.0.0, min API 28/target API 36; declared permissions are location, foreground service, notifications, high-rate sensors, activity recognition, wake lock, Wi-Fi and BLE. There is no internet, camera or microphone permission.
- The checked-in Python decoder validated `campus-recordings-v1.zip`: **240,014 observations across two synthetic sessions and all 14 observation kinds**. One session has 240,000 logical 400 Hz accelerometer observations; the other has one fixture for every kind.
- Latest desktop run of the production recording core processed 240,000 logical motion observations in 4.42 seconds: **54,319 observations/second**, 0 queue drops, about 5.03 MB of journal/chunk data, about 21 compressed bytes/observation, and a sampled peak JVM used heap of about 151 MB. This is a single Windows/JDK/filesystem run, not a phone performance claim. Battery impact was not measured.
- The five instrumentation cases compile into the device-test APK, but have **not been executed**. There is no attached physical device or ADB target. Windows reports no Android Emulator hypervisor driver; attempted headless software-emulation processes crashed with exit code `0xC0000005` before boot/device enumeration.

The final Gradle and test transcripts, Robolectric JUnit XML/HTML, core JUnit XML/HTML, lint reports, decoder validation, APK inspection, signing report, and benchmark are saved in `deliverables/test-results/`.

## Delivered files

- Installable signed sideload release: [deliverables/CampusRecorder-1.0.0.apk](deliverables/CampusRecorder-1.0.0.apk)
- Full portable source plus documents, tests, schema and verification reports: [deliverables/CampusRecorder-source.zip](deliverables/CampusRecorder-source.zip)
- Android instrumentation test package for a physical device: [deliverables/CampusRecorder-device-tests.apk](deliverables/CampusRecorder-device-tests.apk)
- Synthetic example archive: [deliverables/campus-recordings-v1.zip](deliverables/campus-recordings-v1.zip)
- Standalone reference decoder: [tools/decode.py](tools/decode.py)
- Public signing certificate and delivered-file hashes: `deliverables/signing-certificate.pem`, `deliverables/SHA256SUMS.txt`
- Engineering notes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/FORMAT.md](docs/FORMAT.md), [docs/SENSORS.md](docs/SENSORS.md), [docs/BUILD.md](docs/BUILD.md), [docs/TESTING.md](docs/TESTING.md)

The source archive deliberately excludes the private signing key. A workspace-generated RSA 3072 signing key and its local password are stored under ignored `.tools/signing/` for producing updates. Back them up securely if APK updates are required; Android cannot install a replacement release under a different signing identity. The APK is sideloadable and is not published on Play.

## Remaining work to complete the full goal

These are the remaining **field qualification** steps, rather than missing build artifacts:

1. Attach an authorized physical phone or start a stable API 36 emulator; run `.\gradlew.bat :app:connectedDebugAndroidTest`. Inspect failures and logs. Current instrumentation suite covers real default sensor registration/unavailable hardware, framework value preservation, permission-denied radios, private storage/pause/export, and foreground recording across screen off/pause/resume/stop. Record the actual instrumented case results rather than counting compilation as execution.
2. Install the release APK or a fresh debug build on the target phone and manually test all runtime permission paths: precise, approximate-only, denied, later revoked, Android 13+ notification and nearby-device decisions, and disabled radios/location. Confirm denied or unavailable sources become understandable capability warnings while allowed collectors continue.
3. Follow the physical two-hour walk/screen-off/pause/resume/interruption/storage protocol in [docs/TESTING.md](docs/TESTING.md), covering outdoor GNSS, indoor gaps, floors, Wi-Fi scan staleness, known BLE beacons, RTT responders and repeated locations. Decode exports and reconcile session/source/sequence/drop counts.
4. Measure phone thermal and battery/current/charge counters on real devices. The desktop benchmark measures the core file-writing path only; neither Android sensor rate, physical radio delivery nor battery impact is verified by host tests.
5. Fix any confirmed device-specific faults, rerun `tools/build.ps1` or the documented full Gradle/Python commands, rebuild/re-sign if code changes, regenerate all affected checksums/source archive, and update `docs/TESTING.md` plus this file with device model/build, test evidence and outcomes.

Key operating restrictions that must remain visible in any final handoff: Android may terminate or throttle background work; unfiltered BLE may pause with screen off; Wi-Fi scans are system-throttled; GNSS and RTT depend on chipset/environment/access points; no floor or path estimate is made; hardware losses cannot be counted exactly through public sensor callbacks. See `docs/SENSORS.md` for the full capability table and limitations.

## Reproduce the host build

This workspace used Windows, JDK 21, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Android SDK platform 36/build tools 35.0.0. Run:

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$env:ANDROID_HOME='C:\path\to\Android\Sdk'
.\gradlew.bat :core:test :core:synthetic :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug
python -m unittest discover -s tools -p test_decoder.py -v
python tools/decode.py deliverables/campus-recordings-v1.zip
```

On this workspace, `tools/build.ps1` automates those tasks; `docs/BUILD.md` describes SDK installation, signing, and device tests.

## Repository state

This workspace began empty apart from `.git` and remains an uncommitted `main` checkout. Source files and deliverables are present but `git status` reports them as untracked. If requested work involves creating a commit or pushing/publishing elsewhere, inspect Git status and coordinate before doing so; no remote/release publication is part of the current goal.
