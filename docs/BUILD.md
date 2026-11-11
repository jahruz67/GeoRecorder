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

`.github/workflows/android-build.yml` runs on every branch push, pull request, and manual dispatch. It uses JDK 21, installs Android API 36/build tools 35.0.0, runs the core and Android host tests, generates and decodes the synthetic archive, builds the APKs, and runs debug lint. The run uploads APKs, the synthetic example, benchmark, and available test/lint reports as a 14-day artifact. Device instrumentation tests are not run.

After a successful branch push or manual run, it signs `CampusRecorder.apk` and publishes it with `SHA256SUMS.txt` in a GitHub Release tagged `v1.0.<workflow run number>`, targeting the exact built commit. Pull requests cannot publish releases. The build job has read-only repository access; only the release job receives `contents: write`, using GitHub's automatic token. No manually configured secrets are required. Runs do not cancel earlier commits' builds. A push containing multiple commits releases the branch tip; local commits are built once pushed. Re-running a workflow replaces that run's release assets instead of creating a duplicate release.

The fixed PKCS#12 signing key is checked in at `.github/signing/campus-recorder.p12`, with alias and store/key password `campusrecorder`. It is intentionally public for this app's automatic distribution. Keep this same file for future updates. CI sets `releaseVersionCode` to the workflow run number plus one and `releaseVersionName` to `1.0.<workflow run number>`; local builds retain version 1 / `1.0.0` unless those Gradle properties are supplied. Keep this workflow's run numbering when publishing updates, or explicitly choose a version code higher than previously installed releases.

The original `deliverables/CampusRecorder-1.0.0.apk` was signed with a different private key that is absent from this checkout. New workflow releases cannot update that old APK in place. Export recordings before uninstalling the old app, install the first workflow APK, then install later workflow APKs over it normally.

The workflow uses the official `actions/checkout` v7, `actions/setup-java` v6, `android-actions/setup-android` v4, and `actions/upload-artifact` v7 actions. Review action updates as part of normal repository maintenance.

`app/build/outputs/apk/debug/app-debug.apk` is signed with the standard local Android debug identity. `app/build/outputs/apk/release/app-release-unsigned.apk` needs signing before installation. The delivered `CampusRecorder-1.0.0.apk` is a separately signed release build, with `debuggable=false`.

## Release signing

Workflow releases are signed automatically. For a manually built update to a workflow release, supply a `releaseVersionCode` greater than the installed version when building, then sign with the same checked-in key. Android will not accept an update signed by a different identity over an existing installation.

```powershell
apksigner sign --ks .github/signing/campus-recorder.p12 --ks-key-alias campusrecorder --ks-pass pass:campusrecorder --key-pass pass:campusrecorder --out CampusRecorder.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs CampusRecorder.apk
adb install -r CampusRecorder.apk
```

The original delivery's signing identity was stored under ignored `.tools/signing/` in the previous build workspace and excluded from its source archive; it is unavailable here. `deliverables/signing-certificate.pem` is that original APK's public certificate, not the new workflow key's certificate. `deliverables/SHA256SUMS.txt` covers the original delivered files; each workflow release supplies its own APK checksum. APKs are sideloadable and are not published on the Play Store.

## On-device tests

Connect an authorized test phone or boot a working emulator, then run:

```powershell
adb devices -l
.\gradlew.bat :app:connectedDebugAndroidTest
```

The suite tests real framework sensor registration and missing hardware, raw timestamp copying, disk/export behavior, denied radio permissions, and a foreground session through screen off, pause, resume and stop. It grants location to the test app. An emulator cannot validate physical GNSS, BLE, RTT, or battery behavior. Test reports appear under `app/build/reports/androidTests/connected/`.

Run the physical-device field protocol in `TESTING.md` before relying on a device for a mapping campaign. No remote publishing, account setup, or application server is required.
