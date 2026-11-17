#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
cargo +1.91.0 test --locked --manifest-path "$ROOT/Cargo.toml" --target-dir "$ROOT/target-host" --lib
cargo +1.91.0 build --locked --manifest-path "$ROOT/Cargo.toml" --target-dir "$ROOT/target-host"
mkdir -p "$ROOT/target-host/java"
"$JAVA_HOME/bin/javac" -d "$ROOT/target-host/java" "$ROOT"/tests/java/app/notify/android/crypto/*.java
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$ROOT/target-host/debug" \
    -cp "$ROOT/target-host/java" app.notify.android.crypto.JniRoundtrip
