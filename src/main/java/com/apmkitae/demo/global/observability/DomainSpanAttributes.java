package com.apmkitae.demo.global.observability;

import io.opentelemetry.api.common.AttributeKey;

/**
 * 자동 계측 span 에 얹는 도메인 값의 키.
 * <p>
 * OTel semantic convention 에 {@code order.*}/{@code payment.*} 네임스페이스는 없어 현재 충돌하지 않는다.
 * 향후 semconv 에 같은 이름이 등록되면 {@code apmkitae.} 접두를 붙인다.
 * <p>
 * ClickHouse 의 {@code SpanAttributes} 는 {@code Map(LowCardinality(String), String)} 이라
 * long 으로 넣은 값도 문자열로 저장된다 — 조회 시 {@code SpanAttributes['order.id'] = '1'} 처럼 따옴표가 필요하다.
 */
public final class DomainSpanAttributes {

    public static final AttributeKey<Long> ORDER_ID = AttributeKey.longKey("order.id");
    public static final AttributeKey<String> ORDER_STATUS = AttributeKey.stringKey("order.status");
    public static final AttributeKey<Long> PAYMENT_ID = AttributeKey.longKey("payment.id");

    /** 상태 전이 이력을 남기는 span event 이름 */
    public static final String ORDER_STATUS_CHANGED = "order.status.changed";

    /**
     * 결제는 성사됐는데 주문을 확정하지 못한 경우의 event 이름.
     * <p>
     * 이 응답은 409(4xx)라 Agent 가 SERVER span 을 Error 로 기록하지 않는다.
     * event 로 남기지 않으면 "결제만 남은 주문"이 트레이스에서 보이지 않는다.
     */
    public static final String PAYMENT_ORPHANED = "order.payment.orphaned";

    /**
     * 결제는 취소됐는데 주문을 취소하지 못한 경우의 event 이름.
     * <p>
     * 생성 경로의 {@link #PAYMENT_ORPHANED} 와 같은 이유로 필요하다 — 응답이 409(4xx)라
     * SERVER span 이 Error 로 기록되지 않아 event 가 없으면 흔적이 남지 않는다.
     */
    public static final String PAYMENT_OVER_CANCELLED = "order.payment.over-cancelled";

    /**
     * 결제 성사 여부를 알 수 없는 경우의 event 이름.
     * <p>
     * 읽기 타임아웃은 요청이 전달되지 않은 것과 결제가 커밋된 뒤 응답만 못 받은 것을 구분하지 못한다.
     * 응답 코드(502)만으로는 "apm-payment 가 500 을 돌려줌"과 구분되지 않는데, 전자만 결제가 살아남는다.
     */
    public static final String PAYMENT_OUTCOME_UNKNOWN = "order.payment.outcome-unknown";

    private DomainSpanAttributes() {
    }
}
