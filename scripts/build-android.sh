#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
if [[ ! -x "$JAVA_HOME/bin/java" || ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    echo "Android toolchain missing. See README.md for setup instructions." >&2
    exit 1
fi
if [[ $# -eq 0 || "$1" == -* ]]; then
    set -- :app:assembleDebug :app:testDebugUnitTest "$@"
fi
exec ./gradlew "$@"
