#!/usr/bin/env bash
# Starts an Android TV emulator for the smoke tests and waits until it's ready: start-emulator.sh <api> <target> <arch>
# The emulator keeps running for the job's later steps; its log is emulator.log.
#
# Ready means more than sys.boot_completed: once booted, the emulator runs its own adb set-up and the device drops off
# adb for a second or two (seen on Google TV 16: "device offline", then "not found"). A command landing then fails.
# So it waits for the emulator's own "Boot completed" (logged before that set-up), then for the package manager to
# answer three times in a row.
set -euo pipefail

api=$1 target=$2 arch=$3
image="system-images;android-$api;$target;$arch"
tools=$ANDROID_HOME/cmdline-tools/latest/bin
adb=$ANDROID_HOME/platform-tools/adb

yes | "$tools/sdkmanager" --licenses > /dev/null || true
"$tools/sdkmanager" --install emulator platform-tools "$image" > /dev/null
echo no | "$tools/avdmanager" create avd --force --name tv --package "$image" --device tv_1080p

"$adb" start-server
nohup "$ANDROID_HOME/emulator/emulator" -avd tv -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
    > emulator.log 2>&1 &

deadline=$((SECONDS + 600))
waitFor() {
    until "$@"; do
        if ((SECONDS > deadline)); then
            echo "The emulator wasn't ready within 10 minutes." >&2
            tail -n 50 emulator.log >&2
            exit 1
        fi
        sleep 2
    done
}

answers() { "$adb" shell pm path android > /dev/null 2>&1; }
steady() {
    for _ in 1 2 3; do
        answers || return 1
        sleep 2
    done
}

waitFor grep -q "Boot completed" emulator.log
waitFor steady

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" shell settings put global "$scale" 0
done
echo "Emulator ready: Android $("$adb" shell getprop ro.build.version.release | tr -d '\r') ($target, $arch)."
