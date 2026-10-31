#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./scripts/build-android.sh :app:assembleRelease :app:testDebugUnitTest :app:lintRelease "$@"
