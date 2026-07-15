# apm-demo

APM 계측 대상 데모 애플리케이션. OTel Java Agent가 만들어내는 트레이스/메트릭의 원천이 되는 주문(Order) API를 제공한다.

## 기술 스택

- Java 21 (LTS), Spring Boot 3.3.x, Gradle 8.14
- MySQL 8 (Docker), H2 (테스트)
- springdoc-openapi (Swagger UI)
- OpenTelemetry Java Agent (자동 계측)

## 실행

### 1. DB 실행

```bash
docker compose up -d      # MySQL 8 (포트는 .env의 MYSQL_PORT, 기본 3306)
```

### 2-a. 일반 실행

```bash
./gradlew bootRun         # http://localhost:18080
```

### 2-b. OTel Agent 설정 실행 (계측 확인용)

```bash
./scripts/run-with-agent.sh
```

- Agent JAR이 없으면 `scripts/download-otel-agent.sh`가 자동 실행됨 (`otel/`은 gitignore 대상)
- exporter가 콘솔 출력(logging)으로 설정되어 있어, API를 호출하면 **앱 콘솔에 span이 그대로 출력**된다

Agent 실행 옵션:

| 옵션 | 값 | 설명 |
|------|-----|------|
| `otel.service.name` | `apm-demo` | span의 서비스 이름 |
| `otel.traces.exporter` | `logging` | 트레이스를 콘솔에 출력 |
| `otel.metrics.exporter` | `logging` | JVM 메트릭을 콘솔에 출력 (10초 주기) |
| `otel.logs.exporter` | `none` | 로그 신호는 비활성화 |

## 계측 확인 방법

Agent 설정 실행 상태에서:

```bash
# 주문 생성 → 콘솔에 SERVER span(POST /api/orders)과 JDBC CLIENT span(INSERT)이 같은 traceId로 출력
curl -X POST http://localhost:18080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"customer-1","productId":"product-1","quantity":2,"unitPrice":4500}'

# 배송 중 주문 취소(409) → span status ERROR 기록
curl -X POST http://localhost:18080/api/orders/{id}/cancel
```

콘솔 span 출력 형식 (LoggingSpanExporter):

```
'POST /api/orders' : <traceId> <spanId> SERVER ...
'INSERT apm_demo.orders' : <같은 traceId> <spanId> CLIENT ...
```

## API

Swagger UI: `http://localhost:18080/swagger-ui.html`

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/orders` | 주문 생성 (총액 = 단가 × 수량, 서버 계산) |
| GET | `/api/orders/{id}` | 주문 단건 조회 |
| GET | `/api/orders?customerId=` | 주문 목록 조회 (파라미터 없으면 전체) |
| POST | `/api/orders/{id}/cancel` | 주문 취소 (배송 시작 후엔 409) |

## 테스트

```bash
./gradlew test
```

## 컨벤션

공통 개발 컨벤션: [apm-kitae.md](./apm-kitae.md)
