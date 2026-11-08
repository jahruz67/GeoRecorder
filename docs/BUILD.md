# Build, installation, and signing

## Requirements

- JDK 21. Set `JAVA_HOME` to that installation; a newer Java selected by Windows is not sufficient for the pinned Gradle version.
- Android SDK command-line tools; accepted SDK licenses; platform 36; build tools 35.0.0 (AGP default); platform-tools. Build tools 36.0.0 also work for the separate signing commands below.
- Network access for the first Gradle/dependency/SDK download. The installed app works offline.
- Python 3.10+ for the reference decoder and its tests; no Python packages required.

Versions are pinned: Gradle 8.13 (distribution SHA-256 in wrapper properties), Android Gradle Plugin 8.13.2, Kotlin 2.2.21, protobuf compiler/runtime 4.33.0, Gson 2.13.2. Android testing uses Robolectric 4.16.1, AndroidX test runner 1.7.0 and JUnit extension 1.3.0. AndroidX test dependencies are absent from the release application.

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$env:ANDROID_HOME='C:\path\to\Android\Sdk'
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
sdkmanager --licenses
# Use forward slashes for sdk.dir, or let tools/build.ps1 write it.
'sdk.dir=C:/path/to/Android/Sdk' | Set-Content local.properties
.\gradlew.bat :core:test :core:synthetic :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug
python -m unittest discover -s tools -p test_decoder.py -v
python tools/decode.py deliverables/campus-recordings-v1.zip
```

`tools/build.ps1 -JavaHome ... -AndroidSdk ... -Python ...` performs these checks on Windows. On Linux/macOS, use `chmod +x gradlew`, set the same environment variables, write the SDK path to `local.properties`, and invoke `./gradlew` with the same tasks.

The protobuf schema is the single source in `schema/recording.proto`. Gradle generates Java lite classes for the core and copies the schema into generated Android assets before building. No generated schema copy requires manual synchronization.

## GitHub Actions build

`.github/workflows/android-build.yml` runs on every push, pull request, and manual dispatch. It uses JDK 21, installs Android API 36/build tools 35.0.0, runs the core and Android host tests, generates and decodes the synthetic archive, builds debug/unsigned release/device-test APKs, and runs debug lint. The run uploads those unsigned APKs, the synthetic example, benchmark, and available test/lint reports as a 14-day artifact. It does not need repository secrets, sign APKs, publish a release, or run device instrumentation tests. Device tests require an attached authorized device and can be run separately.

The workflow uses the official `actions/checkout` v7, `actions/setup-java` v6, `android-actions/setup-android` v4, and `actions/upload-artifact` v7 actions. Review action updates as part of normal repository maintenance.

`app/build/outputs/apk/debug/app-debug.apk` is signed with the standard local Android debug identity. `app/build/outputs/apk/release/app-release-unsigned.apk` needs signing before installation. The delivered `CampusRecorder-1.0.0.apk` is a separately signed release build, with `debuggable=false`.

## Release signing

Generate and protect your own signing key for subsequent distributions. Keep the same key for updates; Android will not accept an update signed by a different identity over an existing installation.

```powershell
keytool -genkeypair -keystore campus-recorder.p12 -alias campusrecorder -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Campus Recorder'
# The keytool and apksigner commands prompt for your password.
apksigner sign --ks campus-recorder.p12 --ks-key-alias campusrecorder --out CampusRecorder-1.0.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs CampusRecorder-1.0.0.apk
adb install CampusRecorder-1.0.0.apk
```

For this workspace, the generated signing identity and password are stored only under ignored `.tools/signing/`; they are excluded from the source archive. Back them up securely if you intend to update the delivered installation. `deliverables/signing-certificate.pem` contains the public certificate only. `deliverables/SHA256SUMS.txt` covers the delivered files. The APK is sideloadable and is not a Play Store publication.

## On-device tests

Connect an authorized test phone or boot a working emulator, then run:

```powershell
adb devices -l
.\gradlew.bat :app:connectedDebugAndroidTest
```

The suite tests real framework sensor registration and missing hardware, raw timestamp copying, disk/export behavior, denied radio permissions, and a foreground session through screen off, pause, resume and stop. It grants location to the test app. An emulator cannot validate physical GNSS, BLE, RTT, or battery behavior. Test reports appear under `app/build/reports/androidTests/connected/`.

Run the physical-device field protocol in `TESTING.md` before relying on a device for a mapping campaign. No remote publishing, account setup, or application server is required.
