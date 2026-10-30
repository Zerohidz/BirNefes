#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
task_tools="$ANDROID_SDK_ROOT/build-tools/36.0.0"
task_android="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
mkdir -p .build/fixture/classes .build/fixture/dex
"$task_tools/aapt2" link -o .build/fixture/base.apk -I "$task_android" --manifest tests/fixture/AndroidManifest.xml
javac --release 17 -classpath "$task_android" -d .build/fixture/classes tests/fixture/*.java
jar --create --file .build/fixture/classes.jar -C .build/fixture/classes .
"$task_tools/d8" --lib "$task_android" --min-api 29 --output .build/fixture/dex .build/fixture/classes.jar
cp .build/fixture/base.apk .build/fixture/unsigned.apk
(cd .build/fixture/dex && zip -q -u ../unsigned.apk classes*.dex)
"$task_tools/zipalign" -f 4 .build/fixture/unsigned.apk .build/fixture/aligned.apk
"$task_tools/apksigner" sign --ks .build/debug.jks --ks-pass pass:android --out .build/fixture/target.apk .build/fixture/aligned.apk
