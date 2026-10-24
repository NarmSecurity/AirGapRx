# First build: runs the domain tests and the debug APK build, and saves everything under docs\evidence.
# Run from the repo folder in PowerShell (or Android Studio's terminal):   .\scripts\first-build.ps1
# Needs JDK 17+ (Android Studio's bundled JDK, or set JAVA_HOME) and the Android SDK (Android Studio's, or
# the command-line tools in %LOCALAPPDATA%\Android\Sdk).
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$ev = Join-Path $root 'docs\evidence'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

# Use Android Studio's bundled JDK if JAVA_HOME is not set.
if (-not $env:JAVA_HOME) {
  foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
    if (Test-Path "$c\bin\java.exe") { $env:JAVA_HOME = $c; break }
  }
}
# Point Gradle at the SDK if local.properties is missing.
if (-not (Test-Path 'local.properties')) {
  $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
  if (Test-Path $sdk) { "sdk.dir=$($sdk.Replace('\','\\'))" | Set-Content -Encoding ascii 'local.properties' }
}

$log = Join-Path $ev "build-$stamp.log"
"JAVA_HOME=$env:JAVA_HOME" | Out-File -Encoding utf8 $log
$start = Get-Date
& .\gradlew.bat :core:test :app:verifyNoNetworkPermissionDebug :app:assembleDebug --console=plain *>> $log
$code = $LASTEXITCODE
"EXIT=$code" | Out-File -Append -Encoding utf8 $log

# Save only outputs this run produced: an up-to-date or failed task leaves an older report or APK behind.
$report = 'core\build\reports\tests\test'
if ((Test-Path "$report\index.html") -and (Get-Item "$report\index.html").LastWriteTime -ge $start) {
  Copy-Item -Recurse -Force $report (Join-Path $ev "core-test-report-$stamp")
}
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
if ((Test-Path $apk) -and (Get-Item $apk).LastWriteTime -ge $start) {
  (Get-FileHash $apk -Algorithm SHA256).Hash | Out-File -Encoding ascii (Join-Path $ev "app-debug-$stamp.sha256")
}

Write-Host "Build exit code: $code. Log: $log"
