#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="$PWD/.tools/android-sdk"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o .tools/android-cli.zip
  echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  .tools/android-cli.zip' | sha256sum -c -
  unzip -q .tools/android-cli.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm .tools/android-cli.zip
fi
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses >/dev/null || test "${PIPESTATUS[1]}" = 0
"$SDKMANAGER" 'platforms;android-34' 'platforms;android-35' 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' 'ndk;27.0.12077973' 'cmake;3.22.1'
.tools/flutter/bin/flutter config --android-sdk "$ANDROID_HOME"
