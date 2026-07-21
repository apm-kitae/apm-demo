package com.apmkitae.demo.global.observability;

import com.apmkitae.demo.domain.order.entity.Order;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Span.current() 는 GlobalOpenTelemetry 가 아니라 Context.current() 를 읽는다.
 * OpenTelemetryExtension 을 등록하는 것만으로는 no-op span 이 잡혀
 * "통과하지만 아무것도 검증하지 못하는" 테스트가 되므로,
 * 테스트가 직접 span 을 만들어 makeCurrent() 하고 end() 해야 getSpans() 에 보인다.
 */
class DomainSpansTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private SpanData recordWithin(Runnable action) {
        Span span = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("t").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } finally {
            span.end();
        }
        List<SpanData> spans = OTEL.getSpans();
        return spans.get(spans.size() - 1);
    }

    private Order order(Long id, Consumer<Order> transition) {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        ReflectionTestUtils.setField(order, "id", id);
        transition.accept(order);
        return order;
    }

    @Test
    @DisplayName("tagOrder — 활성 span 에 order.id·order.status attribute 를 얹는다")
    void tagOrderSetsAttributes() {
        Order pending = order(1L, o -> {
        });

        SpanData data = recordWithin(() -> DomainSpans.tagOrderTransition(pending));

        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_ID)).isEqualTo(1L);
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("tagOrder — order.id 는 문자열이 아니라 long 키로 들어간다")
    void tagOrderUsesLongKey() {
        Order pending = order(7L, o -> {
        });

        SpanData data = recordWithin(() -> DomainSpans.tagOrderTransition(pending));

        assertThat(data.getAttributes().get(AttributeKey.longKey("order.id"))).isEqualTo(7L);
        assertThat(data.getAttributes().get(AttributeKey.stringKey("order.id"))).isNull();
    }

    @Test
    @DisplayName("tagOrder — 상태 전이를 order.status.changed event 로 남긴다")
    void tagOrderAddsStatusChangedEvent() {
        Order pending = order(1L, o -> {
        });

        SpanData data = recordWithin(() -> DomainSpans.tagOrderTransition(pending));

        assertThat(data.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getName()).isEqualTo(DomainSpanAttributes.ORDER_STATUS_CHANGED);
            assertThat(event.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("PENDING");
        });
    }

    @Test
    @DisplayName("attribute 는 덮어쓰기라 최종 상태만 남지만, event 는 전이마다 쌓인다")
    void attributeKeepsLastStatusWhileEventsAccumulate() {
        Order pending = order(1L, o -> {
        });
        Order confirmed = order(1L, o -> o.confirmPayment(42L));

        SpanData data = recordWithin(() -> {
            DomainSpans.tagOrderTransition(pending);
            DomainSpans.tagOrderTransition(confirmed);
        });

        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("CONFIRMED");
        assertThat(data.getEvents()).extracting(EventData::getName)
                .containsExactly(DomainSpanAttributes.ORDER_STATUS_CHANGED, DomainSpanAttributes.ORDER_STATUS_CHANGED);
        assertThat(data.getEvents()).extracting(e -> e.getAttributes().get(DomainSpanAttributes.ORDER_STATUS))
                .containsExactly("PENDING", "CONFIRMED");
    }

    @Test
    @DisplayName("tagOrderState — attribute 만 얹고 event 는 남기지 않는다 (전이가 아닌 조회 지점)")
    void tagOrderStateSkipsEvent() {
        Order confirmed = order(1L, o -> o.confirmPayment(42L));

        SpanData data = recordWithin(() -> DomainSpans.tagOrderState(confirmed));

        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_ID)).isEqualTo(1L);
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("CONFIRMED");
        assertThat(data.getEvents()).isEmpty();
    }

    @Test
    @DisplayName("tagPayment — payment.id 를 long 키로 얹는다")
    void tagPaymentSetsPaymentId() {
        SpanData data = recordWithin(() -> DomainSpans.tagPayment(42L));

        assertThat(data.getAttributes().get(DomainSpanAttributes.PAYMENT_ID)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Agent 미기동 경로 — 활성 span 이 없어도 예외 없이 통과한다")
    void noOpWhenNoActiveSpan() {
        Order pending = order(1L, o -> {
        });

        assertThatCode(() -> {
            DomainSpans.tagOrderTransition(pending);
            DomainSpans.tagOrderState(pending);
            DomainSpans.tagPayment(42L);
        }).doesNotThrowAnyException();

        assertThat(OTEL.getSpans()).isEmpty();
    }

    @Test
    @DisplayName("주문 ID 가 아직 없으면 order.id 를 붙이지 않는다")
    void skipsNullOrderId() {
        Order unsaved = order(null, o -> {
        });

        SpanData data = recordWithin(() -> DomainSpans.tagOrderTransition(unsaved));

        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_ID)).isNull();
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("PENDING");
    }
}
