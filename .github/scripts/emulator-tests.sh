#!/usr/bin/env bash
# Runs Gravity's instrumented tests on the booted emulator and collects the evidence:
# test XML, the GravityCI log lines, full logcat and real screenshots.
set -u
mkdir -p ci-out
adb logcat -G 16M || true
adb logcat -c || true
adb shell rm -rf /data/local/tmp/gravity-ci
adb shell mkdir -p /data/local/tmp/gravity-ci
adb shell getprop ro.build.fingerprint > ci-out/device.txt

./gradlew --no-daemon connectedDebugAndroidTest --stacktrace > ci-out/gradle-connected.txt 2>&1
code=$?
tail -40 ci-out/gradle-connected.txt

adb logcat -d -v raw -s GravityCI:I > ci-out/gravity-ci.txt || true
adb logcat -d -v threadtime > ci-out/logcat-full.txt || true
adb pull /data/local/tmp/gravity-ci ci-out/screenshots || true
cp -r app/build/outputs/androidTest-results ci-out/ 2>/dev/null || true
echo "$code" > ci-out/exit-code
exit 0
