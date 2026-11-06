#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK}"
task_tools="$ANDROID_SDK_ROOT/build-tools/36.0.0"
task_android="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
task_version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"])')
# BIRNEFES_PUBLIC=1: no personal token, for anyone. BIRNEFES_TEST_HTTP=1: cleartext to a local test server.
task_manifest=AndroidManifest.xml
if [ "${BIRNEFES_TEST_HTTP:-}" = 1 ]; then task_apk="artifacts/BirNefes-${task_version}-http-test.apk"
elif [ "${BIRNEFES_PUBLIC:-}" = 1 ]; then task_apk="artifacts/BirNefes-${task_version}.apk"
else task_apk="artifacts/BirNefes-${task_version}-test.apk"; fi
rm -rf .build/classes .build/dex
mkdir -p .build/classes .build/dex .build/generated artifacts
python3 scripts/generate-sync-config.py
if [ "${BIRNEFES_TEST_HTTP:-}" = 1 ]; then
 task_manifest=.build/AndroidManifest-http.xml
 sed 's/<application /<application android:usesCleartextTraffic="true" /' AndroidManifest.xml > "$task_manifest"
fi
"$task_tools/aapt2" compile --dir res -o .build/resources.zip
"$task_tools/aapt2" link -o .build/base.apk -I "$task_android" --manifest "$task_manifest" .build/resources.zip
javac --release 17 -classpath "$task_android" -d .build/classes src/com/zerohidz/whatsapppause/*.java .build/generated/SyncConfig.java
jar --create --file .build/classes.jar -C .build/classes .
"$task_tools/d8" --lib "$task_android" --min-api 29 --output .build/dex .build/classes.jar
cp .build/base.apk .build/unsigned.apk
(cd .build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$task_tools/zipalign" -f 4 .build/unsigned.apk .build/aligned.apk
# Local test signing only; do not mistake this for a production distribution key.
if [ ! -f .build/debug.jks ]; then
 keytool -genkeypair -keystore .build/debug.jks -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"
fi
"$task_tools/apksigner" sign --ks .build/debug.jks --ks-pass pass:android --out "$task_apk" .build/aligned.apk
"$task_tools/apksigner" verify --verbose "$task_apk"
printf '%s\n' "Built $task_apk (test signed)"
