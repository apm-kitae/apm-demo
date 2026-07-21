# 이슈 #7 — 주문 생성 시 결제 서비스 HTTP 호출 + 도메인 attribute 계측

브랜치: `feat/#7` / base: `develop`

## 목표

`POST /api/orders` 한 번에 주문 PENDING 저장 → apm-payment HTTP 호출 → 주문 CONFIRMED 갱신까지 수행.
하나의 TraceId에 apm-demo·apm-payment span이 이어지고, span attribute·event로 주문 ID와 상태 전이를 역추적할 수 있게 한다.

## 설계

### 트랜잭션 경계 분리

```
tx1  주문 PENDING 저장 + 커밋
     ↓  (트랜잭션 밖)
     POST /api/payments  ← DB 커넥션을 잡지 않은 상태에서 HTTP 호출
     ↓
tx2  주문 CONFIRMED + paymentId 갱신 + 커밋   (실패 시 FAILED 갱신 + 커밋)
```

근거:
- HTTP 호출(최대 3초) 동안 DB 커넥션을 점유하면 커넥션 풀이 고갈된다
- 단일 트랜잭션이면 결제 실패 시 주문 행까지 롤백돼 FAILED 기록이 남지 않는다

구현:
- `OrderService`의 클래스 레벨 `@Transactional(readOnly = true)`를 제거하고 조회 메서드(`findById`·`findOrders`)에 개별 부여한다 — 누락하면 flush mode 최적화를 잃는다
- `create()`·`cancel()`의 `@Transactional`을 **둘 다** 제거한다. `cancel()`에 남기면 결제 취소 HTTP 호출이 트랜잭션 안에서 일어나 커넥션을 점유한다
- 트랜잭션 단위는 `OrderWriter`(별도 빈)로 분리한다. 자기 호출은 프록시를 타지 않아 같은 클래스 안의 메서드 분리로는 `@Transactional`이 적용되지 않는다

### OrderWriter 시그니처 — detached 엔티티 금지

`open-in-view: false`라 트랜잭션 커밋 후 반환된 `Order`는 detached다. detached 인스턴스에 상태 변경 메서드를 호출해도 dirty checking이 없어 UPDATE가 나가지 않는다.
따라서 **엔티티가 아니라 ID를 받아 트랜잭션 안에서 재조회**하고, 갱신된 인스턴스를 반환한다.

```java
@Component
@RequiredArgsConstructor
public class OrderWriter {
    @Transactional                                Order savePending(OrderCreateRequest request);
    @Transactional                                Order confirm(Long orderId, Long paymentId);
    @Transactional(propagation = REQUIRES_NEW)    Order fail(Long orderId);
    @Transactional(readOnly = true)               Order readCancellable(Long orderId);
    @Transactional                                Order cancel(Long orderId);
}
```

- `fail()`이 `REQUIRES_NEW`인 이유: 상위(테스트 등)에 트랜잭션이 걸려 있어도 FAILED 기록이 조용히 롤백되지 않게 한다
- 각 메서드는 `orderRepository.save()`로 **명시 저장**하고 갱신된 `Order`를 반환한다. 기존 `cancel()`은 더티 체킹에 의존해 `save()`가 없는데, 트랜잭션 경계가 바뀌면 그 전제가 사라진다
- 응답 DTO는 각 메서드가 **반환한 인스턴스**로 만든다 — `savePending()`이 돌려준 인스턴스로 만들면 CONFIRMED가 반영되지 않는다

### 새 클래스 배치

기존 구조(`domain/order/{controller,service,repository,entity,dto}` + `global/{config,exception}`)를 따른다.

| 클래스 | 패키지 |
|--------|--------|
| `PaymentClientConfig` | `global/config/payment` (`SwaggerConfig`가 `global/config/swagger`에 있는 선례) |
| `PaymentClient` | `domain/order/client` |
| `PaymentResult` | `domain/order/client/dto` |
| `PaymentCallFailedException` | `global/exception` (`InvalidOrderStatusException`과 동일 형태) |
| `OrderWriter` | `domain/order/service` |
| `DomainSpanAttributes`, `DomainSpans` | `global/observability` (신규 패키지 — 계측은 횡단 관심사) |

상수 클래스 이름을 `OrderSpanAttributes`가 아니라 `DomainSpanAttributes`로 두는 이유: `payment.id`는 order 도메인 밖 키다.

### 결제 클라이언트

| 항목 | 값 |
|------|-----|
| 빈 | `PaymentClient` (`PaymentClientConfig`) — 자동설정 `RestClient.Builder`를 주입받아 설정 후 넘긴다 |
| base-url | `app.payment.base-url` (기본 `http://localhost:18081`) |
| 연결 타임아웃 | `app.payment.connect-timeout-ms` (기본 1000) |
| 읽기 타임아웃 | `app.payment.read-timeout-ms` (기본 3000) — apm-payment 지연 주입 500ms의 6배 |

지연 주입은 `PaymentService.pay()`에만 있고 `cancel()`에는 없다. 정상 응답 최악값이 500ms + INSERT 수준이라 3000ms면 오탐이 나지 않는다.

`PaymentClient` 생성자는 `RestClient.Builder`를 받아 자체적으로 `build()`한다. `MockRestServiceServer.bindTo()`가 `RestTemplate`/`RestClient.Builder`만 받기 때문에, 완성된 `RestClient`를 받으면 클라이언트 단위 테스트를 할 수 없다.

**`RestClient.Builder` 타입의 빈은 선언하지 않는다.** Boot의 `RestClientAutoConfiguration.restClientBuilder`는 `@ConditionalOnMissingBean`(타입 기준) + `@Scope("prototype")`이라,
payment 전용 Builder를 빈으로 노출하면 자동설정이 백오프해 **baseUrl이 payment로 고정된 싱글턴이 앱 전역의 유일한 `RestClient.Builder`가 된다**.
지금은 소비자가 하나뿐이라 사고가 안 나지만, 다음 HTTP 클라이언트를 추가하는 순간 결제 서버로 나간다.
대신 `PaymentClientConfig`가 자동설정 Builder를 주입받아 설정한 뒤 `PaymentClient` 빈을 만든다.

타임아웃은 `RestClient.Builder`에 설정 API가 없다. Boot 3.3에서는 request factory로 주입한다
(`spring.http.client.*` 프로퍼티는 Boot 3.4부터라 이 버전에서 동작하지 않는다):

```java
@Bean
PaymentClient paymentClient(RestClient.Builder builder,      // 자동설정 prototype 빈
                            @Value("${app.payment.base-url}") String baseUrl,
                            @Value("${app.payment.connect-timeout-ms}") long connectMs,
                            @Value("${app.payment.read-timeout-ms}") long readMs) {
    ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(connectMs))
            .withReadTimeout(Duration.ofMillis(readMs));
    return new PaymentClient(builder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(JdkClientHttpRequestFactory.class, settings)));
}
```

`get(settings)` 단일 인자 오버로드를 쓰면 안 된다. 그 분기는 Apache HttpClient5 → Jetty → OkHttp → **`SimpleClientHttpRequestFactory`** 순이라,
클래스패스에 HTTP 클라이언트가 없는 이 프로젝트에서는 `HttpURLConnection`으로 내려간다 (커넥션 풀 없음).
반면 `RestClient`가 requestFactory 없이 쓰는 기본값은 `JdkClientHttpRequestFactory`다 — 타임아웃을 넣으려다 전송 계층이 오히려 구형으로 바뀌는 셈이라, `get(Class, settings)` 오버로드로 JDK HttpClient를 명시한다.
이 선택은 E2E에서 확인할 Agent 계측 이름도 결정한다 (`java-http-client`).

`PaymentClient` 메서드 2개:

- `PaymentResult pay(Long orderId, Long amount)`
- `void cancel(Long paymentId)`

`PaymentResult`는 `record PaymentResult(Long id, String status)` — `id`는 `Order.paymentId`로 저장한다.
(apm-payment 응답은 `{id, orderId, amount, status, createdAt}`이며 나머지 필드는 쓰지 않는다.)

예외 매핑:

| 상황 | RestClient 예외 | 처리 |
|------|-----------------|------|
| `cancel()`의 404·409 | `HttpClientErrorException.NotFound` / `.Conflict` | 정상 반환 (취소 목적 달성) |
| 그 밖의 모든 실패 | `RestClientException` (5xx·4xx·타임아웃·본문 변환 실패 전부의 상위) | `PaymentCallFailedException` |
| 본문이 비었거나 결제 ID 없음 | — | `PaymentCallFailedException` |

`HttpServerErrorException`·`HttpClientErrorException`·`ResourceAccessException` 셋만 잡으면 안 된다.
본문 변환 실패(`UnknownContentTypeException`, 깨진 JSON)는 `RestClientException`을 직접 상속해 셋 중 어느 것도 아니라 그대로 새어나간다.
그러면 `OrderService.create`의 catch에도 걸리지 않아 **결제는 성사됐는데 주문이 PENDING으로 고착**되고 응답은 502가 아닌 500이 된다.

`body(Class)`는 `@Nullable`이라 빈 본문이면 `null`이 온다. 클라이언트 경계에서 막지 않으면 호출부가 NPE를 내고 같은 고착이 발생한다.

`cancel()`이 무시하는 4xx를 404·409로 **한정**한다. 400/401/403까지 삼키면 결제는 COMPLETED인데 주문만 CANCELLED가 되어, 취소 순서를 정한 이유와 정면으로 어긋난다.

`pay()`의 400은 apm-demo가 보낸 요청이 잘못됐다는 뜻이라 자체 버그지만, 응답 규칙은 "결제 서비스 호출이 실패했다" 하나로 통일해 502로 내보낸다.

예외 이름을 `PaymentCallFailedException`으로 두는 이유: apm-payment의 `PaymentFailedException`은 "결제 승인 실패"고 이쪽은 "결제 서비스 호출 실패"라 의미가 다르다.

### 결제 실패 시 제어 흐름

```java
public OrderResponse create(OrderCreateRequest request) {   // @Transactional 없음
    Order pending = orderWriter.savePending(request);
    DomainSpans.tagOrderTransition(pending);
    try {
        PaymentResult result = paymentClient.pay(pending.getId(), pending.getTotalPrice());
        Order confirmed = orderWriter.confirm(pending.getId(), result.id());
        DomainSpans.tagOrderTransition(confirmed);
        DomainSpans.tagPayment(result.id());
        return OrderResponse.from(confirmed);
    } catch (PaymentCallFailedException e) {
        try {
            DomainSpans.tagOrderTransition(orderWriter.fail(pending.getId()));
        } catch (RuntimeException failToRecord) {
            e.addSuppressed(failToRecord);   // 실패 기록이 또 실패해도 원 원인을 잃지 않는다
        }
        throw e;                                            // 502로 전파
    }
}
```

`fail()`이 던지면 원래 `PaymentCallFailedException`이 사라져 응답이 500이 되고 `recordException`도 타지 않는다 —
계측이 목적인 이슈에서 하필 실패 경로의 근거가 비므로 `addSuppressed`로 두 원인을 함께 남긴다.

### 결제 호출 구간의 상태 전이 가드

tx1 커밋과 tx2 사이(최대 3초)에 같은 주문에 취소가 들어올 수 있다.
`confirm()`이 현재 상태를 보지 않으면 CANCELLED를 CONFIRMED로 덮어써, 취소 200을 이미 응답한 주문이 되살아난다.

`Order.confirmPayment`·`Order.fail`은 **PENDING인 주문만** 전이시키고 아니면 `InvalidOrderStatusException`을 던져 409로 나간다.
이 경우 결제는 남고 주문은 CANCELLED로 유지된다 — 보상 처리는 범위 밖이라 그대로 둔다.

409는 4xx라 Agent가 SERVER span을 `Error`로 기록하지 않는다. 결제와 주문이 어긋난 사실이 트레이스에서 사라지므로 event로 직접 남긴다.

| event | 상황 |
|-------|------|
| `order.payment.orphaned` | 결제는 성사됐는데 주문을 확정하지 못함 (생성) |
| `order.payment.over-cancelled` | 결제는 취소됐는데 주문을 취소하지 못함 (취소) |

**한계**: 이 검사는 애플리케이션 레벨 read-then-write이고 조회에 락이 없다.
`confirm` 트랜잭션이 PENDING을 읽은 **뒤** 취소가 커밋되는 ms 단위 구간은 막지 못한다 — 낙관적 락(`@Version`)은 범위 밖이다.
막으려는 대상은 초 단위인 결제 호출 구간이다.

### 취소 순서

```
① OrderWriter.readCancellable(id)  — 조회 + 취소 가능 검증 (SHIPPED/DELIVERED면 409)
② paymentId 있으면 PaymentClient.cancel(paymentId)
③ OrderWriter.cancel(id)           — CANCELLED 갱신
```

결제 취소를 주문 취소보다 먼저 두는 이유: 순서를 뒤집으면 결제 취소가 실패했을 때 주문만 CANCELLED이고 결제는 COMPLETED로 남는다.
①의 검증을 위해 `Order.validateCancellable()`을 분리하고 `cancel()`이 이를 호출한다 — 예외 타입·메시지는 그대로 두어 기존 테스트가 통과한다.
③은 ID로 **재조회**한다. ①에서 조회한 인스턴스는 ② 사이에 트랜잭션이 끝나 detached다.

알려진 한계 (범위 제외이지만 기록):
- **②·③ 사이 실패**: 결제만 CANCELLED이고 주문은 CONFIRMED로 남는다. 보상 트랜잭션은 범위 밖이지만, `order.payment.over-cancelled` event로 흔적은 남긴다
- **①·③ 사이 상태 변경(TOCTOU)**: ① 통과 후 다른 요청이 SHIPPED로 바꿔도 ③의 `Order.cancel()`이 검증을 한 번 더 수행해 걸린다 — 의도된 이중 검증
- **이미 CANCELLED인 주문 재취소**: `validateCancellable()`은 SHIPPED/DELIVERED만 막으므로 통과하고, 결제 취소가 다시 호출돼 409를 받고 무시된다 — 멱등 동작으로 허용 (기존 `cancel()` 동작과 동일)

### 에러 응답

`PaymentCallFailedException` → `GlobalExceptionHandler` → **502 Bad Gateway** + `{"message": "..."}`.
5xx라야 OTel Agent가 SERVER span의 `StatusCode`를 Error로 기록한다 (HTTP semconv: SERVER는 ≥500, CLIENT는 ≥400).

핸들러에서 `Span.current().recordException(e)`를 함께 호출한다. `@ResponseStatus`로 예외를 삼키면 서블릿 컨테이너까지 올라가지 않아 Agent가 붙이는 `exception` span event와 `StatusMessage`가 비어 있다.
`recordException`을 넣으면 ClickHouse `Events.Name='exception'`으로 실패 원인을 역추적할 수 있다. 상태 코드는 Agent가 Error로 설정하므로 `setStatus`는 불필요하다.

### 도메인 attribute·event 계측

| 키 | 타입 | 부여 시점 |
|----|------|-----------|
| `order.id` | long | 주문 저장 직후, 취소 대상 조회 직후 |
| `order.status` | String | 상태가 바뀔 때마다 — **덮어쓰기라 최종 상태만 남는다** |
| `payment.id` | long | 결제 성공 직후, 결제 취소 호출 직전 |

span attribute는 키 단위 set 시맨틱이라 PENDING → CONFIRMED로 두 번 set하면 CONFIRMED만 남는다.
ClickHouse `SpanAttributes`도 `Map(LowCardinality(String), String)`이라 키당 값 1개뿐이다.
**전이 이력은 span event로 남긴다** — ClickHouse 스키마에 `Events Nested(Timestamp, Name, Attributes)`가 있어 그대로 적재된다.

```java
Span.current().addEvent("order.status.changed",
        Attributes.of(stringKey("order.status"), status.name()));
```

`order.status` attribute = 최종 상태(필터용), `order.status.changed` event = 전이 이력(워터폴에서 시각 순으로 표시).

계측은 두 갈래다 — 호출 지점이 전이인지 아닌지에 따라 갈린다.

| 메서드 | 하는 일 | 쓰는 곳 |
|--------|---------|---------|
| `tagOrderTransition(Order)` | attribute set + `order.status.changed` event | 이번 요청에서 상태가 바뀐 지점 |
| `tagOrderState(Order)` | attribute set만 | 상태가 바뀌지 않은 지점 (취소 대상 조회 직후) |

조회에까지 event를 남기면 과거에 일어난 전이가 이번 요청에서 다시 일어난 것처럼 보여, 워터폴에서 전이를 시각 순으로 읽는다는 이 event의 존재 이유가 무너진다.

기대 event 개수 — **생성 2건**(PENDING·CONFIRMED, 실패 시 PENDING·FAILED), **취소 1건**(CANCELLED).

부착 위치 전제:
- Agent 2.x는 컨트롤러 telemetry가 기본 비활성이라 서비스 계층의 `Span.current()`는 SERVER span이다
- spring-data 계측은 기본 활성이라 `OrderRepository.save`가 INTERNAL span을 만든다. repository 호출 **바깥**에서 부여해야 SERVER span에 붙는다 — 위 흐름은 `save()` 반환 이후라 안전
- Agent 미기동 시 `Span.current()`는 no-op span을 반환하고 `setAttribute`는 조용히 버려진다. `isRecording()` 가드로 불필요한 문자열 변환을 건너뛴다

ClickHouse 저장 형태: 컨슈머 `SpanRowMapper.stringify()`가 `INT_VALUE → String.valueOf`로 바꾸므로 long `1`은 `'1'`로 저장된다.
`SpanAttributes['order.id'] = 1`(숫자 비교)은 타입 불일치로 실패하고 `= '1'`이어야 한다. 인덱스는 `mapKeys`에만 걸려 있어 값 필터는 스캔이다.

테스트 구조 — `Span.current()`는 `GlobalOpenTelemetry`가 아니라 `Context.current()`를 읽는다.
`OpenTelemetryExtension` 등록만으로는 no-op span이 잡혀 "통과하지만 아무것도 검증 못 하는" 테스트가 된다.
테스트가 직접 span을 만들어 `makeCurrent()` 하고 `end()` 해야 `getSpans()`에 보인다.

```java
@RegisterExtension static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

Span span = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("t").startSpan();
try (Scope ignored = span.makeCurrent()) {
    DomainSpans.tagOrder(order);
} finally {
    span.end();
}
assertThat(OTEL.getSpans()).singleElement().satisfies(d ->
        assertThat(d.getAttributes().get(AttributeKey.longKey("order.id"))).isEqualTo(1L));
```

"Agent 미기동 시 no-op"는 프로덕션 경로 얘기이고, 테스트가 span을 current로 만들기 때문에 no-op이 아니게 된다 — 서로 다른 상황이다.

### 의존성

```gradle
implementation 'io.opentelemetry:opentelemetry-api'
testImplementation 'io.opentelemetry:opentelemetry-sdk-testing'
```

버전을 쓰지 않는다. `io.spring.dependency-management` 플러그인이 적용돼 있어 Spring Boot 3.3.3의 BOM이 OTel 버전을 **1.37.0**으로 관리한다
(`spring-boot-dependencies-3.3.3.pom`의 `<opentelemetry.version>1.37.0</opentelemetry.version>`, `opentelemetry-bom`을 import하므로 `opentelemetry-api`·`sdk-testing` 모두 해당).
`platform('io.opentelemetry:opentelemetry-bom:1.63.0')`을 선언해도 Spring Boot BOM이 우선해 덮인다.

> 실측 완료: `compileClasspath`·`runtimeClasspath` 모두 `opentelemetry-api -> 1.37.0`, `runtimeClasspath`에 sdk 없음.
> (1차 검증 때 적었던 1.38.0은 Boot 3.3.13을 쓰는 apm-payment 쪽 값이었다.)

이 버전으로 두는 것이 안전한 이유: Agent 2.29.0(스크립트에 `AGENT_VERSION="${AGENT_VERSION:-v2.29.0}"`으로 고정)이 담고 있는 core는 1.63.0이라 **API가 Agent보다 낮다**.
계획서가 쓰는 API(`addEvent(String, Attributes)`, `recordException`, `isRecording`, `AttributeKey.longKey/stringKey`, `Attributes.of`)는 모두 1.x 초기부터 존재해 버전 차이의 영향을 받지 않는다.
Agent의 api-bridge는 자기보다 낮은 API 버전을 브리징한다. 위험한 방향은 그 반대(API가 Agent보다 높음)이고, 그 경우 `Span.current()`가 조용히 no-op을 반환해 attribute가 무음으로 소실된다.

구현체는 Agent가 런타임에 제공하므로 `opentelemetry-sdk`는 `implementation`으로 넣지 않는다.
`sdk-testing`은 `testImplementation`이라 `runtimeClasspath`에 sdk가 들어가지 않는 것을 실측으로 확인했다.

### 실행 스크립트 exporter 변경

`scripts/run-with-agent.sh`가 `-Dotel.traces.exporter=logging`으로 고정돼 있어 span이 콘솔에만 출력되고 Collector로 가지 않는다.
시스템 프로퍼티가 환경변수보다 우선하므로 `OTEL_TRACES_EXPORTER=otlp`를 줘도 덮이지 않는다.
이 상태로는 #7의 E2E를 검증할 수 없다. apm-payment의 같은 스크립트와 동일하게 맞춘다:

| 옵션 | 값 |
|------|-----|
| `otel.traces.exporter` / `otel.metrics.exporter` | `otlp` |
| `otel.exporter.otlp.endpoint` | `${OTLP_ENDPOINT:-http://localhost:4318}` |
| `otel.exporter.otlp.protocol` | `http/protobuf` |

**위 옵션만 바꾸고 나머지는 유지한다.** apm-payment 스크립트에는 없지만 apm-demo에는 있는 `-Dotel.metric.export.interval=10000`을 "동일하게 맞춘다"며 지우면 메트릭 주기가 기본값 60초로 바뀐다.
스크립트 상단 주석("exporter는 콘솔 출력(logging) — Collector 없이 계측 결과를 눈으로 확인하는 용도")도 함께 고친다.

### 엔티티·DTO 변경

- `Order.paymentId` (Long, nullable)
- `Order.confirmPayment(Long paymentId)` — CONFIRMED + paymentId
- `Order.fail()` — FAILED
- `Order.validateCancellable()`
- `OrderResponse.paymentId` 추가 + `@Schema` (기존 DTO는 모든 필드에 `@Schema`)

마이그레이션 — **운영 DB 수동 적용용**. dev는 `ddl-auto: update`, 테스트는 `create-drop`이라 자동 반영되므로 `validate` 환경에서만 필요하다.

```sql
ALTER TABLE orders ADD COLUMN payment_id bigint NULL;
```

### 기존 테스트 격리

`OrderApiTest`는 `@SpringBootTest`라 실제 `PaymentClient`가 `localhost:18081`로 나간다. 결제 서버 없이 도는 CI에서 connection refused → 502가 된다.
또 `createOrder`가 PENDING을 단언하는데 CONFIRMED로 바뀐다.

- `OrderApiTest`에 `@MockBean PaymentClient`를 두고 `pay()`·`cancel()` 응답을 지정한다
- `createOrder` 단언을 CONFIRMED + `paymentId`로 수정한다
- `cancelOrder` 계열은 `paymentId`가 없는 주문이라 결제 취소 호출이 없어야 한다 — `verify(paymentClient, never())`
- `application-test.yml`의 `app.payment.base-url`을 **확실히 연결 거부되는 주소**로 고정한다. `application.yml`의 기본값(18081)이 상속되면, 목킹을 빠뜨린 테스트가 로컬에 떠 있는 apm-payment로 나가 실제 결제를 만든다

## 작업 목록

### 준비
- [x] build.gradle — `opentelemetry-api`, `opentelemetry-sdk-testing` 추가 (버전 없이). `compileClasspath`·`runtimeClasspath` 모두 **1.37.0** 해석, `runtimeClasspath`에 sdk 미포함 실측 확인
- [x] application.yml / application-test.yml — `app.payment.*` 설정 추가. 테스트는 `base-url: http://localhost:1`로 고정해 목킹 누락 시 즉시 연결 거부
- [x] `scripts/run-with-agent.sh` — exporter를 otlp로 변경, 엔드포인트·프로토콜 명시. `metric.export.interval=10000` 유지, 상단 주석 정정

### RED → GREEN → REFACTOR
- [x] Order 엔티티 — `paymentId`, `confirmPayment`, `fail`, `validateCancellable`
- [x] `DomainSpanAttributes` + `DomainSpans` — attribute·event 부여, `makeCurrent()` 스코프로 실제 검증, `order.id`가 `longKey`인지 단언, no-op 경로에서 예외 없이 통과
- [x] `PaymentClient` — 정상 응답 매핑 / 500 / `ResourceAccessException` / 취소 404·409 무시 / 취소 400 전파 / `pay()` 400 전파 (MockRestServiceServer)
- [x] `OrderWriter` — savePending / confirm / fail / readCancellable / cancel, ID 재조회로 UPDATE가 실제로 나가는지 검증
- [x] **기존 `OrderApiTest` 격리 선행** — `@MockBean PaymentClient` 추가, `createOrder` 단언을 CONFIRMED + `paymentId`로 수정. `OrderService.create` 구현보다 **먼저** 해야 중간 단계에서 스위트가 빨간불로 남지 않는다
- [x] `OrderService.create` — 성공 시 CONFIRMED·paymentId, 실패 시 FAILED 후 예외 전파 (Mockito). attribute·event 검증은 `OpenTelemetryExtension` + 테스트가 만든 span을 `makeCurrent()` 한 스코프 안에서 `create()`를 호출하고 `OTEL.getSpans()`로 단언 — `DomainSpans`가 정적 유틸이라 Mockito로는 verify할 수 없다
- [x] `OrderService.cancel` — 결제 취소 호출 분기, 호출 순서(InOrder), 취소 경로 `payment.id` 부여 (위와 같은 하니스)
- [x] `GlobalExceptionHandler` — `PaymentCallFailedException` → 502 + `recordException`
- [x] `OrderApiTest`에 결제 실패 502 테스트 추가, `cancelOrder`에 `verify(paymentClient, never())`
- [x] `OrderController` `@ApiResponses` — 주문 생성·취소에 502 추가

### README 정정

exporter를 otlp로 바꾸면 기존 서술이 사실과 어긋나므로 함께 고친다.

- [x] Agent 옵션 표 — `logging` 행을 `otlp`·엔드포인트·프로토콜로 교체
- [x] "exporter가 콘솔 출력(logging)으로…" 문단과 `LoggingSpanExporter` 출력 예시를 ClickHouse 조회 예시로 교체
- [x] API 표에 502 추가
- [x] 결제 연동 흐름·`app.payment.*` 설정 표·두 서비스 동시 실행 방법
- [x] attribute·event 키 목록 + 조회 예시 (값은 String이라 따옴표 필수, `StatusCode`는 `Ok`/`Error`/`Unset` — 컨슈머 `SpanRowMapper`가 접두어 제거 후 첫 글자만 대문자로 저장. ClickHouse 실측 확인)
- [x] 마이그레이션 SQL (운영 DB 수동 적용용임을 명시)

### 마무리
- [x] `./gradlew test` 전체 통과
- [ ] Critic 3회 검증
- [ ] `/code-review`

### E2E 수동 검증 (이슈 #7 완료 판정 기준)

단위 테스트로는 traceparent 전파를 확인할 수 없다 — Agent가 없으면 헤더가 붙지 않는다.
apm-infra·apm-consumer 기동 후 apm-payment(18081) → apm-demo(18080) 순으로 `run-with-agent.sh`를 띄우고 확인한다.

- [x] **CLIENT span 존재 확인** — request factory를 `JdkClientHttpRequestFactory`로 명시했으므로 Agent의 `java-http-client` 계측이 잡아야 CLIENT span과 traceparent가 생긴다. 없으면 TraceId가 끊기므로 여기서 중단하고 클라이언트 계측부터 해결한다 (`SimpleClientHttpRequestFactory`로 떨어졌다면 계측 이름은 `http-url-connection`이다)
  ```bash
  docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
    --query "SELECT SpanName, SpanKind FROM otel.otel_traces WHERE ServiceName='apm-demo' AND SpanKind='Client' ORDER BY Timestamp DESC LIMIT 5"
  ```
- [x] **주문 ID로 TraceId 역추적** (값은 String이라 따옴표 필수)
  ```bash
  docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
    --query "SELECT TraceId, ServiceName, SpanName, StatusCode FROM otel.otel_traces WHERE SpanAttributes['order.id'] = '1'"
  ```
- [x] **같은 TraceId에 두 서비스 span 적재**
  ```bash
  docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
    --query "SELECT ServiceName, SpanName, SpanKind, StatusCode, Duration/1e6 AS ms FROM otel.otel_traces WHERE TraceId = '{TraceId}' ORDER BY Timestamp"
  ```
- [x] **attribute 부착 위치가 SERVER span인지 확인**
  ```bash
  docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
    --query "SELECT SpanKind, SpanName FROM otel.otel_traces WHERE SpanAttributes['order.id'] != ''"
  ```
- [x] **상태 전이 event 확인** — `Events.Name`에 `order.status.changed`가 시각 순으로 남는지
  ```bash
  docker exec apm-clickhouse clickhouse-client -u apm --password 1234 \
    --query "SELECT SpanName, e.1 AS ts, e.2 AS name, e.3 AS attrs FROM otel.otel_traces ARRAY JOIN arrayZip(Events.Timestamp, Events.Name, Events.Attributes) AS e WHERE TraceId = '{TraceId}' AND e.2 IN ('order.status.changed', 'exception') ORDER BY ts"
  ```
- [x] **`Transaction.commit` span 2개** — create 경로에서 `savePending`·`confirm` 두 트랜잭션이 각각 커밋되므로 2개. (주장이 아니라 확인 대상으로 둔다)
- [ ] **Grafana 트레이스 검색** — 워터폴 **Services 2**, 주문 span 아래 결제 Client/Server span 계층
  > attribute 노출은 판정 기준에서 뺀다. `trace-search.json`의 워터폴 패널·span 상세 패널 모두 `SpanAttributes`를 조회하지 않아 현재 대시보드로는 확인할 수 없다. attribute 확인은 위 ClickHouse 쿼리로 대체하고, 대시보드에 `SpanAttributes` 컬럼을 추가하는 건 apm-infra 후속 이슈로 분리한다
- [x] **에러 전파** — `APP_FAULT_ERROR_RATE=1`로 apm-payment 기동 → apm-payment SERVER span과 apm-demo SERVER span 모두 `StatusCode='Error'`, `Events.Name='exception'` 존재

## 범위 제외

재시도·서킷브레이커·보상 트랜잭션·멱등성 키·외부 PG 연동·프론트 2단계 결제 흐름

## 검증 이력

### 1차 (3개 병렬)
- **Critic 1** (트랜잭션·프레임워크): NEEDS_IMPROVEMENT — 10건 중 9건 반영, 1건 기각
  - 기각: "OTel Agent는 `Transaction.commit` span을 만들지 않는다" → ClickHouse 실측으로 존재 확인 (apm-demo 31건, apm-payment 47건)
- **Critic A** (트랜잭션·프레임워크): NEEDS_IMPROVEMENT — 신규 5건 반영, 1건 기각
  - 기각: "`download-otel-agent.sh`가 `latest`를 받는다" → 실제로는 `AGENT_VERSION="${AGENT_VERSION:-v2.29.0}"`으로 고정
- **Critic B** (계약·일관성·범위): NEEDS_IMPROVEMENT — 신규 7건 반영
  - 최대 수확: OTel BOM 선언이 Spring Boot BOM에 덮여 `1.63.0 -> 1.38.0`으로 해석됨을 `./gradlew dependencies`로 확인, BOM 제거로 정정
- **Critic C** (관측성·OTel API): NEEDS_IMPROVEMENT — 신규 6건 반영, 1건 기각 (Critic A와 동일한 Agent 버전 오판)
  - 최대 수확: `order.status` attribute는 덮어쓰기라 전이 이력이 남지 않음 → span event 병행으로 정정

### 2차 (개정본 대상)
- **2차 A** (개정본 잔여 결함): NEEDS_IMPROVEMENT — 9건 전부 반영
  - OTel 관리 버전은 1.37.0 (Boot 3.3.3 pom 확인) — 1차에서 적은 1.38.0은 apm-payment(Boot 3.3.13) 쪽 값이었다
  - `ClientHttpRequestFactories.get(settings)` 단일 인자는 `SimpleClientHttpRequestFactory`를 고른다 → `get(JdkClientHttpRequestFactory.class, settings)`로 명시
  - ClickHouse `StatusCode`는 `Ok`/`Error`/`Unset` (ClickHouse 실측: `Unset`, `Error`)
  - `RestClient.Builder` 빈 선언이 Boot 자동설정을 백오프시킴 → `PaymentClient` 빈만 노출
  - Grafana 대시보드가 `SpanAttributes`를 조회하지 않아 attribute 노출은 판정 기준에서 제외
  - `otel.metric.export.interval=10000` 유지, `DomainSpans` 정적 유틸은 Mockito verify 불가, event 확인 쿼리 추가

### 3차 (최종)
- **3차**: **OK** — 구현을 막는 BLOCKER/MAJOR 없음. MINOR 5건 전부 반영
  - 문서 내 1.37.0/1.38.0 숫자 충돌, 준비 항목 완료 표시, `Transaction.commit` 판정 단서가 죽은 조건(`save()` 명시 저장이므로 dirty checking 아님)
  - `OrderApiTest` 격리를 `OrderService.create` 구현보다 앞으로 이동 — 순서대로면 중간 3단계 동안 스위트가 실패 상태로 남는다
  - `order.status.changed` event 부여 주체 미명시 → `tagOrder()`가 attribute set과 event 추가를 함께 수행하도록 확정

## 구현 후 검증 이력

### 1차 (3개 병렬 — 정확성·관측성·완결성)

세 Critic 모두 NEEDS_IMPROVEMENT. 실제 버그 4건을 잡아 수정했다.

| 지적 | 내용 | 조치 |
|------|------|------|
| A[2] BLOCKER급 | 결제 호출 구간에 취소가 들어오면 `confirm()`이 CANCELLED를 CONFIRMED로 덮어씀 | `Order.confirmPayment`/`fail`에 PENDING 가드 + 테스트 4건 |
| A[1]·C[9] MAJOR | 본문 변환 실패가 `RestClientException` 직속이라 catch를 통과 → 주문 PENDING 고착·500 응답 | `RestClientException`으로 확대 + 테스트 3건 |
| B[1] MAJOR | 취소 대상 **조회**에 event를 남겨 과거 전이가 이번 요청처럼 보임 | `tagOrderState`/`tagOrderTransition` 분리 + 테스트 2건 |
| A[3] MAJOR | `OrderWriter`의 `@Transactional`을 다 지워도 테스트가 통과 (`save()`가 merge로 UPDATE 발행) | `TestTransaction`으로 `REQUIRES_NEW` 검증. `REQUIRED`로 바꾸면 실패하는 것 확인 |
| A[4] | `body(Class)`의 null 역참조 | 클라이언트 경계에서 차단 |
| A[5]·B[2] | 실패 기록이 또 실패하면 원 예외 소실 | `addSuppressed` + 테스트 |
| A[6] | 실패할 수 없는 테스트 1건 | 삭제하고 전이 가드 테스트로 교체 |
| B[3]·C[7] | `recordException` 미검증 | `GlobalExceptionHandlerTest` 신규 |
| B[4] | README event 쿼리에 `SpanName`·`SpanKind` 누락 | 복원 |
| B[5] | 취소 경로 event 미단언 | 단언 추가 |
| C[2] | todo.md 체크박스 미갱신 | 갱신 |
| C[5] | README 502 설명이 취소 경로에서 부정확 | 정정 |
| A[7]·B[6]·C[4] | `application-test.yml` 주석의 포트 번호 오기 | 정정 |
| C[8] | 인라인 완전수식 클래스명, `recordWithin` 시그니처 불일치 | import 정리·`Runnable` 통일 |

보류:
- **C[1]** README `## 컨벤션` 섹션 삭제 — 이번 작업 이전부터 있던 미커밋 변경이라 손대지 않음. 사용자 확인 대기
- **C[3]** `apm-kitae.md` 변경은 #7과 무관 — `[docs]` 커밋으로 분리
- **C[6]** apm-payment README의 "apm-demo는 logging exporter" 서술이 거짓이 됨 — 별도 레포 후속 `[docs]` 작업

### 2차 — **OK** (BLOCKER/MAJOR 없음)

Critic이 뮤테이션으로 테스트 유효성까지 확인했다 — 1차 수정 7건을 각각 되돌리면 대응 테스트가 실패한다.
MINOR 4건 전부 반영:

| 지적 | 조치 |
|------|------|
| README attribute 표가 빈 줄로 쪼개져 orphan 행이 별도 표로 렌더링 | 빈 줄 제거 |
| README 응답 표에 생성 경로 409 누락 (Swagger·코드와 불일치) | 두 상황 병기 |
| PENDING 가드가 tx 겹침 구간(ms)의 lost update는 못 막음 | `@Version`은 범위 밖으로 두고 한계를 javadoc·README·계획서에 명시 |
| 취소 경로에 생성 경로와 대칭인 event가 없음 | `order.payment.over-cancelled` 추가 + 테스트 |

### `/code-review` — **APPROVE** (CRITICAL 0, HIGH 0)

MEDIUM 3건·LOW 5건. 이번 이슈 목적(트레이스만으로 설명 가능)에 직결되는 것을 반영했다.

| 지적 | 조치 |
|------|------|
| MEDIUM 읽기 타임아웃 시 결제만 남는데 event 없음 — apm-payment는 커밋, apm-demo는 FAILED | `order.payment.outcome-unknown` 추가. `ResourceAccessException` 원인일 때만 남겨 500 응답과 구분 |
| MEDIUM 결제 없는 주문 취소가 트랜잭션 2개·SELECT 2회 | 유지. 분기를 늘리는 비용이 이득보다 크다 |
| MEDIUM 인증 없는 취소가 이제 외부 결제 취소를 발동 | README에 "범위 제외" 표 신설, 위험을 명시 |
| LOW 이미 취소된 주문 재취소가 없던 전이를 event로 남김 | 전이 여부로 `tagOrderState`/`tagOrderTransition` 분기 |
| LOW `getOrder` 중복 | `OrderService` 쪽 제거 |
| LOW `PaymentResult.status`를 파싱만 하고 미검증 | `COMPLETED` 가드 추가 — 상대 서비스가 상태를 늘리면 걸린다 |
| LOW `@Value` 3개 → `@ConfigurationProperties` | 유지. apm-payment의 `FaultConfig`와 동일 방식이라 레포 간 일관성 우선 |
| LOW `application.yml`의 DB 비밀번호 기본값 | #7 범위 밖. 별도 이슈 |
