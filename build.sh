#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/../.android-build"
export JAVA_HOME="$BUILD/jdk"
export ANDROID_HOME="$BUILD/sdk"
export PATH="$JAVA_HOME/bin:$PATH"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/local.properties"
exec "$BUILD/gradle-8.7/bin/gradle" -p "$ROOT" assembleDebug "$@"
