#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle) and install it on the TV as the home screen.
#   launcher/build-and-install.sh            build + install
#   launcher/build-and-install.sh --build    build only
#   OVERLAY=examples/vu-glo-google-tv/res launcher/build-and-install.sh   per-TV config over res/
set -euo pipefail
[ -n "${OVERLAY:-}" ] && OVERLAY="$(cd "$OVERLAY" && pwd)"
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# Google TV stock launcher; older Android TV sets use com.google.android.tvlauncher.
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
OVERLAY_ARGS=()
if [ -n "${OVERLAY:-}" ]; then
  "$BT/aapt2" compile --dir "$OVERLAY" -o "$B/overlay.zip"
  OVERLAY_ARGS=(-R "$B/overlay.zip")
fi
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 30 --target-sdk-version 34 "$B/res.zip" ${OVERLAY_ARGS[@]+"${OVERLAY_ARGS[@]}"}
javac -encoding UTF-8 -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

PY=../tools/venv/bin/python
"$PY" -I ../tools/tv.py --push-b64 "$B/tvhome.apk" /data/local/tmp/tvhome.apk
# Only take over Home if the install worked; otherwise the TV would be left with no home screen.
out=$(printf '%s\n' "pm install -r /data/local/tmp/tvhome.apk" "rm -f /data/local/tmp/tvhome.apk" \
  | "$PY" -I ../tools/tv.py --batch)
echo "$out"
echo "$out" | grep -q '^Success' || { echo "pm install failed; stock launcher left enabled"; exit 1; }
printf '%s\n' \
  "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION" \
  "appops set com.mihir.tvhome GET_USAGE_STATS allow" \
  "pm grant com.mihir.tvhome android.permission.READ_EXTERNAL_STORAGE" \
  "pm grant com.mihir.tvhome android.permission.READ_MEDIA_IMAGES" \
  "pm grant com.mihir.tvhome android.permission.WRITE_SECURE_SETTINGS" \
  "cmd notification allow_listener com.mihir.tvhome/com.mihir.tvhome.MediaListener" \
  "mkdir -p /sdcard/Pictures/Wallpapers" \
  "settings put secure screensaver_components com.mihir.tvhome/.AerialDream" \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith" \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "am force-stop com.mihir.tvhome" \
  "am force-stop com.android.tv.settings" \
  "input keyevent KEYCODE_HOME" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote."
