#!/usr/bin/env bash
# OTel Java Agent를 설정해서 애플리케이션 실행
# exporter는 OTLP — span·메트릭이 Collector(4318) → Kafka → ClickHouse 로 적재된다
# 시스템 프로퍼티가 환경변수보다 우선하므로 OTEL_TRACES_EXPORTER 로는 이 값을 덮을 수 없다
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
    -Dotel.traces.exporter=otlp \
    -Dotel.metrics.exporter=otlp \
    -Dotel.exporter.otlp.endpoint="${OTLP_ENDPOINT:-http://localhost:4318}" \
    -Dotel.exporter.otlp.protocol=http/protobuf \
    -Dotel.logs.exporter=none \
    -Dotel.metric.export.interval=10000 \
    -jar "$APP_JAR"
