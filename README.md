# Campus Recorder

Native Kotlin Android acquisition app for pedestrian mapping. Open the app, tap **Start recording**, and walk freely. Pause, resume, and stop from the app; stop is also available in the foreground notification. Sessions stay in private device storage until you explicitly export them.

The application records observations; path reconstruction belongs in a separate processing engine. It requires no account, map imagery, campus boundary, network API, camera, or microphone. Android 9 / API 28 or later is supported; this build targets API 36.

## Delivered artifacts

- `deliverables/CampusRecorder-1.0.0.apk`: signed release APK, with debugging disabled.
- `deliverables/CampusRecorder-source.zip`: source, Gradle wrapper, schema, decoder, documentation, tests, and the synthetic example.
- `deliverables/campus-recordings-v1.zip`: explicitly marked **synthetic** example containing 240,000 motion observations and 14 fixtures covering every observation kind; generated through the same recording/export core used on Android.
- `schema/recording.proto`: version 1 protobuf schema.
- `tools/decode.py`: streaming reference decoder using only the Python standard library.
- `deliverables/test-results/`: host test reports, build/lint reports, and benchmark results.

Install the APK with Android's package installer or `adb install deliverables/CampusRecorder-1.0.0.apk`. Grant location permission when starting; precise location enables GNSS/radio measurements. Missing optional permissions reduce available streams and are recorded explicitly. Open **Sessions** after stopping to export one session or all saved sessions.

```sh
python tools/decode.py deliverables/campus-recordings-v1.zip
python tools/decode.py deliverables/campus-recordings-v1.zip --jsonl observations.jsonl
```

The decoder validates every listed SHA-256, gzip CRC, message frame, chunk trailer, sequence order, and observation count before reporting success. JSONL is streamed and may contain a valid prefix if a later frame fails validation; always check the exit status.

## Development

See [build instructions](docs/BUILD.md), [architecture and reliability](docs/ARCHITECTURE.md), [recording format](docs/FORMAT.md), [sensor support and limitations](docs/SENSORS.md), and [verification results](docs/TESTING.md).

The current implementation state, delivered artifacts, verification evidence and outstanding physical-device qualification steps are recorded in [CURRENT_STATUS.md](CURRENT_STATUS.md).

GitHub Actions runs the Android builds, core/Android host tests, lint, synthetic export, and reference-decoder tests on every push, pull request, or manual dispatch. Download the unsigned APKs, synthetic example and test reports from the workflow run's `campus-recorder-build-*` artifact. It does not sign or publish a release APK.

The desktop build, host tests, and synthetic benchmark are reproducible. Physical GNSS/radio performance, manufacturer battery policies, screen-off operation, and battery impact still require device validation. The compiled instrumentation suite includes a screen-off service test. Native emulator execution was unavailable on the build host; details are in the verification report. This is not a claim of completed field qualification.
