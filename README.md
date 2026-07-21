# apm-demo

APM 계측 대상 데모 애플리케이션. OTel Java Agent가 만들어내는 트레이스/메트릭의 원천이 되는 주문(Order) API를 제공한다.
주문 생성 시 결제 서비스(apm-payment)를 HTTP로 호출해, 하나의 TraceId에 두 서비스 span이 이어지는 분산 트레이싱을 만든다.

```
apm-demo (주문) ──HTTP + traceparent──▶ apm-payment (결제)
     │                                        │
     └─ OTel Agent ──▶ Collector ◀── OTel Agent ┘
                          │
                          ▼
                  Kafka → apm-consumer → ClickHouse → Grafana
```

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
- exporter가 **OTLP**라 span·메트릭이 Collector(4318) → Kafka → ClickHouse로 적재된다

Agent 실행 옵션:

| 옵션 | 값 | 설명 |
|------|-----|------|
| `otel.service.name` | `apm-demo` | span의 서비스 이름 |
| `otel.traces.exporter` / `otel.metrics.exporter` | `otlp` | Collector로 전송 |
| `otel.exporter.otlp.endpoint` | `http://localhost:4318` | `OTLP_ENDPOINT`로 오버라이드 가능 |
| `otel.exporter.otlp.protocol` | `http/protobuf` | Collector의 OTLP HTTP 리시버 |
| `otel.logs.exporter` | `none` | 로그 신호는 비활성화 |
| `otel.metric.export.interval` | `10000` | 메트릭 export 주기 (ms) |

> 시스템 프로퍼티가 환경변수보다 우선하므로 `OTEL_TRACES_EXPORTER`로는 위 값을 덮을 수 없다.

### 3. 두 서비스 함께 실행 (분산 트레이싱)

apm-infra(Collector·Kafka·ClickHouse)와 apm-consumer가 기동된 상태에서 결제 서비스를 먼저 띄운다.

```bash
# 터미널 1 — 결제 서비스 (18081)
cd ../apm-payment && ./scripts/run-with-agent.sh

# 터미널 2 — 주문 서비스 (18080)
./scripts/run-with-agent.sh
```

## 결제 연동

주문 생성은 세 구간으로 나뉜다. 결제 호출을 트랜잭션 **밖**에 두어야 응답을 기다리는 동안 DB 커넥션을 점유하지 않고,
결제가 실패해도 주문 행이 롤백으로 사라지지 않아 FAILED 기록이 남는다.

```
tx1  주문 PENDING 저장 + 커밋
     ↓
     POST /api/payments  →  apm-payment (traceparent 자동 전파)
     ↓
tx2  CONFIRMED + paymentId 갱신   (실패 시 FAILED)
```

주문 취소는 **결제 취소를 먼저** 호출한다. 순서를 뒤집으면 결제 취소가 실패했을 때 주문만 CANCELLED이고 결제는 COMPLETED로 남는다.

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `app.payment.base-url` | `http://localhost:18081` | `PAYMENT_BASE_URL`로 오버라이드 |
| `app.payment.connect-timeout-ms` | 1000 | 연결 타임아웃 |
| `app.payment.read-timeout-ms` | 3000 | 읽기 타임아웃 — apm-payment 지연 주입(500ms)의 6배 |

결제 서비스의 5xx·타임아웃·연결 실패·예상 밖 4xx는 모두 **502**로 응답한다. 5xx라야 Agent가 SERVER span을 `StatusCode=Error`로 기록한다.
취소 시 404(결제 없음)·409(이미 취소됨)는 취소 목적이 달성된 상태라 통과시킨다.

## 도메인 attribute·event

Agent는 `UPDATE orders`라는 SQL은 알아도 그것이 어느 주문을 어떤 상태로 바꾸는지 모른다.
`Span.current()`에 값을 얹어 주문 ID로 트레이스를 역추적할 수 있게 한다.

| 키 | 종류 | 설명 |
|----|------|------|
| `order.id` | attribute (long) | 주문 ID |
| `order.status` | attribute (String) | **최종** 상태 — attribute는 덮어쓰기라 마지막 값만 남는다 |
| `payment.id` | attribute (long) | 결제 ID |
| `order.status.changed` | event | 상태 전이 이력 — 전이마다 쌓이므로 PENDING → CONFIRMED가 시각 순으로 보인다 |
| `order.payment.orphaned` | event | 결제는 성사됐는데 주문을 확정하지 못한 경우 (생성) |
| `order.payment.over-cancelled` | event | 결제는 취소됐는데 주문을 취소하지 못한 경우 (취소) |
| `order.payment.outcome-unknown` | event | 타임아웃이라 결제 성사 여부를 알 수 없는 경우 |

주문 생성은 결제 호출 구간(최대 3초) 사이에 취소가 들어올 수 있다. `confirmPayment`/`fail`은 PENDING인 주문만 전이시켜,
이미 취소된 주문이 CONFIRMED로 되살아나지 않게 막는다 — 이 경우 결제는 남고 주문은 CANCELLED로 유지되며, 보상 처리는 범위 밖이다.

취소 경로에도 대칭인 구간이 있다. 결제 취소가 성공한 뒤 다른 요청이 상태를 SHIPPED로 바꾸면 주문 취소가 막혀 결제만 취소된 채 남는다.

두 경우 모두 응답이 409(4xx)라 Agent가 SERVER span을 `Error`로 기록하지 않는다. event가 이 상황의 유일한 흔적이라,
아래 쿼리로 결제와 주문이 어긋난 건을 찾는다.

읽기 타임아웃은 세 번째 경우다. apm-payment는 결제를 커밋했는데 apm-demo는 응답을 못 받아 주문을 FAILED로 끝낼 수 있다.
502(5xx)라 SERVER span은 `Error`로 잡히지만, "타임아웃"과 "apm-payment가 500을 돌려줌"이 응답 코드로는 구분되지 않는다 — 전자만 결제가 살아남는다.
그래서 타임아웃일 때만 `order.payment.outcome-unknown`을 남긴다.

```bash
docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
  --query "SELECT TraceId, e.2 AS name, e.3 AS attrs FROM otel.otel_traces ARRAY JOIN arrayZip(Events.Timestamp, Events.Name, Events.Attributes) AS e WHERE e.2 IN ('order.payment.orphaned', 'order.payment.over-cancelled', 'order.payment.outcome-unknown')"
```

> 상태 검사는 애플리케이션 레벨 read-then-write이라 초 단위인 결제 호출 구간만 막는다.
> 트랜잭션이 겹치는 ms 단위 구간은 낙관적 락(`@Version`)이 필요하고, 이는 범위 밖이다.

## 범위 제외

APM 파이프라인 관측이 목적이라 아래는 구현하지 않는다. 이 레포를 참고 구현으로 쓸 때 주의할 것.

| 항목 | 현재 상태 |
|------|-----------|
| **인증·인가** | 없다. `POST /api/orders/{id}/cancel`에 소유자 검증이 없어, 주문 ID를 훑는 것만으로 **다른 사람의 결제를 취소**시킬 수 있다 |
| 보상 트랜잭션·사가 | 결제와 주문이 어긋나면 event로 흔적만 남기고 되돌리지 않는다 |
| 멱등성 키 | 재시도 시 이중 결제를 막지 않는다 |
| 낙관적 락 | 트랜잭션이 겹치는 구간의 lost update를 막지 않는다 |
| 재시도·서킷브레이커 | 결제 서비스 장애 시 그대로 502를 낸다 |

## 계측 확인 방법

```bash
# 주문 생성 → 두 서비스 span이 같은 TraceId로 적재
curl -X POST http://localhost:18080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"customer-1","productId":"product-1","quantity":2,"unitPrice":4500}'
```

```bash
# 1) 주문 ID로 TraceId 역추적
#    SpanAttributes 는 Map(LowCardinality(String), String) 이라 값이 항상 문자열 — 따옴표 필수
docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
  --query "SELECT TraceId, ServiceName, SpanName, StatusCode FROM otel.otel_traces WHERE SpanAttributes['order.id'] = '1'"

# 2) 그 TraceId의 두 서비스 span (분산 트레이싱 성립 여부)
docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
  --query "SELECT ServiceName, SpanName, SpanKind, StatusCode, Duration/1e6 AS ms FROM otel.otel_traces WHERE TraceId = '{TraceId}' ORDER BY Timestamp"

# 3) 상태 전이 event — SpanName·SpanKind 를 같이 봐야 SERVER span 에 붙었는지 확인된다
docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
  --query "SELECT SpanName, SpanKind, e.1 AS ts, e.2 AS name, e.3 AS attrs FROM otel.otel_traces ARRAY JOIN arrayZip(Events.Timestamp, Events.Name, Events.Attributes) AS e WHERE TraceId = '{TraceId}' AND e.2 IN ('order.status.changed', 'exception') ORDER BY ts"
```

주문 생성 트레이스에는 `order.status.changed` event가 **2건**(PENDING·CONFIRMED), 취소 트레이스에는 **1건**(CANCELLED) 남는다.
취소 대상 조회는 전이가 아니라 attribute만 얹고 event를 남기지 않는다.

`StatusCode`는 `Ok`/`Error`/`Unset`, `SpanKind`는 `Server`/`Client`/`Internal`로 저장된다 (컨슈머가 `STATUS_CODE_` 접두어를 떼고 첫 글자만 대문자로 변환).
`SpanAttributes`의 인덱스는 키(`mapKeys`)에만 걸려 있어 값 필터는 파티션 내 스캔이다.

Grafana의 **APM / 트레이스 검색** 대시보드에서 워터폴로도 확인할 수 있다 — `Services 2`가 뜨면 분산 트레이싱이 성립한 것이다.

## API

Swagger UI: `http://localhost:18080/swagger-ui.html`

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/orders` | 주문 생성 + 결제 호출 (총액 = 단가 × 수량, 서버 계산) |
| GET | `/api/orders/{id}` | 주문 단건 조회 |
| GET | `/api/orders?customerId=` | 주문 목록 조회 (파라미터 없으면 전체) |
| POST | `/api/orders/{id}/cancel` | 주문 취소 (결제가 있으면 결제 취소도 호출) |

| 응답 | 상황 |
|------|------|
| 201 | 주문 생성·결제 성공 (CONFIRMED) |
| 400 | 요청 값 검증 실패 |
| 404 | 주문 없음 |
| 409 | 취소 시: 배송 시작 이후라 취소 불가 / 생성 시: 결제 호출 중 주문이 취소돼 확정 불가 |
| 502 | 결제 서비스 호출 실패 — 생성 시 주문은 FAILED로, 취소 시 주문은 직전 상태 그대로 남는다 |

## DB 마이그레이션

`payment_id`는 nullable이라 `ddl-auto: update`가 자동 반영한다. 아래 SQL은 **운영 DB가 `validate`인 경우에만** 수동 적용한다.

```sql
ALTER TABLE orders ADD COLUMN payment_id bigint NULL;
```

## 테스트

```bash
./gradlew test
```

## 관련 레포

| 레포 | 역할 |
|------|------|
| [apm-payment](https://github.com/apm-kitae/apm-payment) | 결제 서비스 — 이 서비스가 HTTP로 호출 |
| [apm-infra](https://github.com/apm-kitae/apm-infra) | Kafka·ClickHouse·Collector·Grafana (Docker Compose) |
| [apm-consumer](https://github.com/apm-kitae/apm-consumer) | Kafka → ClickHouse 적재 |

## 개발 컨벤션

조직 공통 컨벤션은 [apm-kitae.md](./apm-kitae.md) 참고.
