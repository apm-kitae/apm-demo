package com.apmkitae.demo.global.observability;

import com.apmkitae.demo.domain.order.entity.Order;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

/**
 * OTel Java Agent 가 만든 현재 활성 span 에 도메인 값을 얹는다. 새 span 을 만들지 않는다.
 * <p>
 * Agent 는 SQL 문("UPDATE orders")은 알아도 그것이 어느 주문을 어떤 상태로 바꾸는지는 알 수 없다.
 * 여기서 얹는 값이 있어야 주문 ID 로 트레이스를 역추적할 수 있다.
 * <p>
 * 부착 위치: Agent 2.x 는 컨트롤러 telemetry 가 기본 비활성이라 서비스 계층의 {@code Span.current()} 는
 * SERVER span 이다. 반면 spring-data 계측은 기본 활성이라 repository 호출 <b>안</b>에서 부르면
 * 그 INTERNAL span 에 붙는다 — repository 반환 이후에 호출해야 한다.
 * <p>
 * Agent 미기동 시 {@code Span.current()} 는 no-op span 을 반환하고 값은 조용히 버려진다.
 */
public final class DomainSpans {

    private DomainSpans() {
    }

    /**
     * 주문 ID·상태를 attribute 로만 얹는다. <b>이번 요청에서 상태가 바뀌지 않은</b> 지점에 쓴다.
     * <p>
     * 취소 경로에서 취소 대상을 조회한 직후가 여기 해당한다 — 조회는 전이가 아니므로
     * event 를 남기면 과거에 일어난 전이가 이번 요청에서 다시 일어난 것처럼 보인다.
     */
    public static void tagOrderState(Order order) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }
        setOrderAttributes(span, order);
    }

    /**
     * attribute 를 얹고 상태 전이를 event 로 남긴다. <b>이번 요청에서 상태가 바뀐</b> 지점에 쓴다.
     * <p>
     * attribute 는 키 단위 덮어쓰기라 여러 번 호출하면 최종 상태만 남는다.
     * PENDING → CONFIRMED 같은 이력은 event 로만 확인할 수 있어 둘을 함께 수행한다.
     */
    public static void tagOrderTransition(Order order) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }
        setOrderAttributes(span, order);
        span.addEvent(DomainSpanAttributes.ORDER_STATUS_CHANGED,
                Attributes.of(DomainSpanAttributes.ORDER_STATUS, order.getStatus().name()));
    }

    private static void setOrderAttributes(Span span, Order order) {
        if (order.getId() != null) {
            span.setAttribute(DomainSpanAttributes.ORDER_ID, order.getId());
        }
        span.setAttribute(DomainSpanAttributes.ORDER_STATUS, order.getStatus().name());
    }

    /**
     * 결제가 성사된 뒤 주문을 확정하지 못했다는 사실을 남긴다.
     * 응답이 409(4xx)라 SERVER span 이 Error 로 잡히지 않으므로, 이 event 가 유일한 흔적이다.
     */
    public static void recordOrphanedPayment(Long orderId, Long paymentId) {
        addPaymentMismatchEvent(DomainSpanAttributes.PAYMENT_ORPHANED, orderId, paymentId);
    }

    /**
     * 결제는 취소됐는데 주문을 취소하지 못했다는 사실을 남긴다.
     * 생성 경로의 {@link #recordOrphanedPayment} 와 대칭이다.
     */
    public static void recordOverCancelledPayment(Long orderId, Long paymentId) {
        addPaymentMismatchEvent(DomainSpanAttributes.PAYMENT_OVER_CANCELLED, orderId, paymentId);
    }

    /**
     * 결제가 성사됐는지 알 수 없다는 사실을 남긴다 (읽기 타임아웃·연결 끊김).
     * 결제 ID 를 받지 못한 상태라 주문 ID 만 담는다.
     */
    public static void recordUnknownPaymentOutcome(Long orderId) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }
        span.addEvent(DomainSpanAttributes.PAYMENT_OUTCOME_UNKNOWN,
                Attributes.of(DomainSpanAttributes.ORDER_ID, orderId));
    }

    private static void addPaymentMismatchEvent(String eventName, Long orderId, Long paymentId) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }
        span.addEvent(eventName, Attributes.of(
                DomainSpanAttributes.ORDER_ID, orderId,
                DomainSpanAttributes.PAYMENT_ID, paymentId));
    }

    public static void tagPayment(Long paymentId) {
        Span span = Span.current();
        if (!span.isRecording() || paymentId == null) {
            return;
        }
        span.setAttribute(DomainSpanAttributes.PAYMENT_ID, paymentId);
    }
}
