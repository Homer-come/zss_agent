#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
SERVER_PORT="${SERVER_PORT:-18081}" mvn -pl backend spring-boot:run
