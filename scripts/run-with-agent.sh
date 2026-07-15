#!/usr/bin/env bash
# OTel Java Agent를 설정해서 애플리케이션 실행
# exporter는 콘솔 출력(logging) — Collector 없이 계측 결과를 눈으로 확인하는 용도
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_PATH="$PROJECT_DIR/otel/opentelemetry-javaagent.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "Agent JAR 없음 → 다운로드 실행"
    "$PROJECT_DIR/scripts/download-otel-agent.sh"
fi

echo "애플리케이션 빌드"
(cd "$PROJECT_DIR" && ./gradlew bootJar -q)

APP_JAR=$(ls "$PROJECT_DIR"/build/libs/apm-demo-*.jar | head -1)

# Java 21 선택: JAVA_HOME이 21이면 그대로, 아니면 macOS java_home으로 탐색
if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_BIN="$(/usr/libexec/java_home -v 21)/bin/java"
else
    JAVA_BIN="java"
fi
echo "Java: $("$JAVA_BIN" -version 2>&1 | head -1)"

echo "Agent 설정으로 실행: $APP_JAR"
exec "$JAVA_BIN" \
    -javaagent:"$JAR_PATH" \
    -Dotel.service.name=apm-demo \
    -Dotel.traces.exporter=logging \
    -Dotel.metrics.exporter=logging \
    -Dotel.logs.exporter=none \
    -Dotel.metric.export.interval=10000 \
    -jar "$APP_JAR"
