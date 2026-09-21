#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools
if [[ ! -x .tools/flutter/bin/flutter ]] || [[ $(cat .tools/flutter.version 2>/dev/null) != 3.47.5 ]]; then
  curl -fL --retry 3 https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.47.5-stable.tar.xz -o .tools/flutter.tar.xz
  echo '2132e990f236f8d22e7c6314b29a191a95b10d7cbcfec9b4e2e303d996652cbb  .tools/flutter.tar.xz' | sha256sum -c -
  rm -rf .tools/flutter
  tar --no-same-owner -xf .tools/flutter.tar.xz -C .tools
  rm .tools/flutter.tar.xz
  echo 3.47.5 > .tools/flutter.version
fi
export PATH="$PWD/.tools/flutter/bin:$PATH"
if [[ $(stat -c %u .tools/flutter) != $(id -u) ]]; then
  chown -R "$(id -u):$(id -g)" .tools/flutter
fi
flutter config --no-analytics
flutter pub get --enforce-lockfile
