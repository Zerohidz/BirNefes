#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
task_tools="$ANDROID_SDK_ROOT/build-tools/36.0.0"
task_android="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
mkdir -p .build/pane-fixture/classes .build/pane-fixture/dex
"$task_tools/aapt2" link -o .build/pane-fixture/base.apk -I "$task_android" --manifest tests/pane-fixture/AndroidManifest.xml
javac --release 17 -classpath "$task_android" -d .build/pane-fixture/classes tests/pane-fixture/PaneActivity.java
jar --create --file .build/pane-fixture/classes.jar -C .build/pane-fixture/classes .
"$task_tools/d8" --lib "$task_android" --min-api 29 --output .build/pane-fixture/dex .build/pane-fixture/classes.jar
cp .build/pane-fixture/base.apk .build/pane-fixture/unsigned.apk
(cd .build/pane-fixture/dex && zip -q -u ../unsigned.apk classes*.dex)
"$task_tools/zipalign" -f 4 .build/pane-fixture/unsigned.apk .build/pane-fixture/aligned.apk
"$task_tools/apksigner" sign --ks .build/debug.jks --ks-pass pass:android --out .build/pane-fixture/target.apk .build/pane-fixture/aligned.apk
