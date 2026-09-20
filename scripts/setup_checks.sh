#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools
if [[ ! -x .tools/flutter/bin/flutter ]] || [[ $(cat .tools/flutter.version 2>/dev/null) != 3.44.0 ]]; then
  curl -fL --retry 3 https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.44.0-stable.tar.xz -o .tools/flutter.tar.xz
  echo 'e1ec95e6c550458a34de93580cb85dac24da0e9bedb9bb42811f050ac5a0c7d5  .tools/flutter.tar.xz' | sha256sum -c -
  rm -rf .tools/flutter
  tar --no-same-owner -xf .tools/flutter.tar.xz -C .tools
  rm .tools/flutter.tar.xz
  echo 3.44.0 > .tools/flutter.version
fi
export PATH="$PWD/.tools/flutter/bin:$PATH"
if [[ $(stat -c %u .tools/flutter) != $(id -u) ]]; then
  chown -R "$(id -u):$(id -g)" .tools/flutter
fi
flutter config --no-analytics
flutter pub get --enforce-lockfile
