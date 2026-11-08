param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Python = 'python'
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Set JAVA_HOME to JDK 21, or provide -JavaHome.' }
if (!$AndroidSdk -or !(Test-Path -LiteralPath $AndroidSdk)) { throw 'Set ANDROID_HOME to your Android SDK, or provide -AndroidSdk.' }
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$AndroidSdk
$sdkSetting=$AndroidSdk.Replace('\','/')
Set-Content -LiteralPath local.properties -Value "sdk.dir=$sdkSetting" -Encoding ascii
& .\gradlew.bat :core:test :core:synthetic :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle checks failed.' }
& $Python -m unittest discover -s tools -p test_decoder.py -v
if ($LASTEXITCODE -ne 0) { throw 'Reference decoder tests failed.' }
& $Python tools/decode.py deliverables/campus-recordings-v1.zip
if ($LASTEXITCODE -ne 0) { throw 'Example validation failed.' }
Write-Output 'Build and host tests passed. Release signing and on-device tests are documented in docs/BUILD.md.'
