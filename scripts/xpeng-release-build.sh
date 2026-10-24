#!/bin/sh
# Car-test release build for the xpeng flavor. Run this yourself; the keystore
# password never leaves your shell.
set -eu
cd "$(dirname "$0")/.."

export XPENG_KEYSTORE_PATH="${XPENG_KEYSTORE_PATH:-$HOME/.android/diplay-xpeng.jks}"
export XPENG_KEYSTORE_PASSWORD="$(security find-generic-password -s diplay-xpeng-keystore -w)"
# Shared from the main checkout: this worktree's own .private is untracked and empty.
export DIPLAY_AUTH_ASSETS_DIR="/Users/aloaiza/Development/Personal/DiPlay/.private/diplay-0.2.8-assets/assets"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

./gradlew :mobile:assembleXpengRelease
