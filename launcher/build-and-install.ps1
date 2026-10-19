# Windows version of build-and-install.sh: build the launcher APK with the bare SDK tools (no Gradle)
# and install it on the TV as the home screen.
#   launcher\build-and-install.ps1            build + install
#   launcher\build-and-install.ps1 -BuildOnly build only
#   launcher\build-and-install.ps1 -Overlay examples\vu-glo-google-tv\res   per-TV config over res/
# Needs PowerShell 7 (pwsh; Windows PowerShell 5.1 aborts on the JDK tools' stderr notes),
# JDK 17 and an Android SDK with platforms;android-34 and build-tools;34.0.0.
# Override locations with $env:JAVA_HOME / $env:ANDROID_HOME.
#Requires -Version 7
param([switch]$BuildOnly, [string]$Overlay)
$ErrorActionPreference = 'Stop'
if ($Overlay) { $Overlay = (Resolve-Path $Overlay).Path }
Set-Location $PSScriptRoot
$Toolchain = Join-Path $env:USERPROFILE 'tvhome-toolchain'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem $Toolchain -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName }
$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $Toolchain 'sdk' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$BT = "$SDK\build-tools\34.0.0"
$JAR = "$SDK\platforms\android-34\android.jar"
# Google TV stock launcher; older Android TV sets use com.google.android.tvlauncher.
$StockLauncher = if ($env:STOCK_LAUNCHER) { $env:STOCK_LAUNCHER } else { 'com.google.android.apps.tv.launcherx' }
$PY = '..\tools\venv\Scripts\python.exe'
$B = 'build'

function Run { $rest = @($args | Select-Object -Skip 1); & $args[0] @rest; if ($LASTEXITCODE) { throw "failed: $($args -join ' ')" } }

if (Test-Path $B) { Remove-Item -Recurse -Force $B }
New-Item -ItemType Directory -Force "$B\gen", "$B\classes", "$B\dex" | Out-Null
Run "$BT\aapt2.exe" compile --dir res -o "$B\res.zip"
$overlayArgs = @()
if ($Overlay) {
  Run "$BT\aapt2.exe" compile --dir $Overlay -o "$B\overlay.zip"
  $overlayArgs = @('-R', "$B\overlay.zip")
}
Run "$BT\aapt2.exe" link -o "$B\base.apk" -I $JAR --manifest AndroidManifest.xml --java "$B\gen" `
  -A assets --min-sdk-version 30 --target-sdk-version 30 "$B\res.zip" @overlayArgs
$sources = Get-ChildItem src, "$B\gen" -Recurse -Filter *.java | ForEach-Object FullName
Run javac -encoding UTF-8 -nowarn '-Xlint:-options' --release 11 -classpath $JAR -d "$B\classes" @sources
$classes = Get-ChildItem "$B\classes" -Recurse -Filter *.class | ForEach-Object FullName
Run "$BT\d8.bat" --min-api 30 --lib $JAR --output "$B\dex" @classes
Copy-Item "$B\base.apk" "$B\unsigned.apk"
# Append classes.dex without touching the other entries (resources.arsc must stay uncompressed).
Run $PY -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" "$B\unsigned.apk" "$B\dex\classes.dex"
Run "$BT\zipalign.exe" -f 4 "$B\unsigned.apk" "$B\aligned.apk"
if (-not (Test-Path debug.keystore)) {
  Run keytool -genkeypair -keystore debug.keystore -storepass android -keypass android `
    -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome"
}
Run "$BT\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --out "$B\tvhome.apk" "$B\aligned.apk"
Write-Host "built $B\tvhome.apk ($((Get-Item "$B\tvhome.apk").Length) bytes)"
if ($BuildOnly) { exit 0 }

Run $PY -I ..\tools\tv.py --push-b64 "$B\tvhome.apk" /data/local/tmp/tvhome.apk
# Only take over Home if the install worked; otherwise the TV would be left with no home screen.
$installed = @("pm install -r /data/local/tmp/tvhome.apk", "rm -f /data/local/tmp/tvhome.apk") | & $PY -I ..\tools\tv.py --batch
$installed | Write-Host
if (-not ($installed -match '^Success')) { throw "pm install failed; stock launcher left enabled" }
@(
  "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION"
  "appops set com.mihir.tvhome GET_USAGE_STATS allow"
  "pm grant com.mihir.tvhome android.permission.READ_EXTERNAL_STORAGE"
  "mkdir -p /sdcard/Pictures/Wallpapers"
  "settings put secure screensaver_components com.mihir.tvhome/.AerialDream"
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity"
  "pm disable-user --user 0 $StockLauncher"
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity"
  # Clear the stale home task left by switching launchers, or Home opens behind the current app.
  "am force-stop com.mihir.tvhome"
  "am force-stop com.android.tv.settings"
  "input keyevent KEYCODE_HOME"
) | & $PY -I ..\tools\tv.py --batch
Write-Host "Installed. Press Home on the remote."
