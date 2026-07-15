#!/usr/bin/env bash
# OpenTelemetry Java Agent JAR 다운로드
# JAR은 수십 MB 바이너리이므로 레포에 커밋하지 않고 로컬에서 받아서 사용한다 (otel/ 디렉터리는 .gitignore 대상)
set -euo pipefail

OTEL_DIR="$(cd "$(dirname "$0")/.." && pwd)/otel"
JAR_PATH="$OTEL_DIR/opentelemetry-javaagent.jar"

# 버전 고정이 필요하면 예: VERSION="v2.10.0" 로 바꾸고 URL을 download/${VERSION}/ 로 수정
DOWNLOAD_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar"

if [ -f "$JAR_PATH" ]; then
    echo "이미 존재: $JAR_PATH"
    exit 0
fi

mkdir -p "$OTEL_DIR"
echo "다운로드 중: $DOWNLOAD_URL"
curl -fL --progress-bar -o "$JAR_PATH" "$DOWNLOAD_URL"
echo "완료: $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"
