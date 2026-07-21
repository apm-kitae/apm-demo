package com.apmkitae.demo.domain.order.service;

import com.apmkitae.demo.domain.order.client.PaymentClient;
import com.apmkitae.demo.domain.order.client.dto.PaymentResult;
import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.dto.OrderResponse;
import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import com.apmkitae.demo.domain.order.repository.OrderRepository;
import com.apmkitae.demo.global.exception.InvalidOrderStatusException;
import com.apmkitae.demo.global.exception.PaymentCallFailedException;
import com.apmkitae.demo.global.observability.DomainSpanAttributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DomainSpans 는 정적 유틸이라 Mockito 로 호출을 verify 할 수 없다.
 * 테스트가 직접 span 을 makeCurrent() 한 스코프 안에서 서비스를 호출하고,
 * 종료된 span 의 attribute·event 를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderWriter orderWriter;

    @Mock
    private PaymentClient paymentClient;

    @InjectMocks
    private OrderService orderService;

    private static final OrderCreateRequest REQUEST =
            new OrderCreateRequest("customer-1", "product-1", 2, 4500L);

    private Order order(Long id, Consumer<Order> transition) {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        ReflectionTestUtils.setField(order, "id", id);
        transition.accept(order);
        return order;
    }

    private SpanData recordWithin(Runnable action) {
        Span span = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("POST /api/orders").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } finally {
            span.end();
        }
        List<SpanData> spans = OTEL.getSpans();
        return spans.get(spans.size() - 1);
    }

    @Test
    @DisplayName("create — 결제가 성공하면 CONFIRMED 와 결제 ID 를 반환한다")
    void createConfirmsOnPaymentSuccess() {
        Order pending = order(1L, o -> {
        });
        Order confirmed = order(1L, o -> o.confirmPayment(42L));
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L)).willReturn(new PaymentResult(42L, "COMPLETED"));
        given(orderWriter.confirm(1L, 42L)).willReturn(confirmed);

        OrderResponse response = orderService.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.paymentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("create — 주문 저장 → 결제 호출 → 상태 확정 순서로 실행한다")
    void createRunsInOrder() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L)).willReturn(new PaymentResult(42L, "COMPLETED"));
        given(orderWriter.confirm(1L, 42L)).willReturn(order(1L, o -> o.confirmPayment(42L)));

        orderService.create(REQUEST);

        InOrder inOrder = inOrder(orderWriter, paymentClient);
        inOrder.verify(orderWriter).savePending(REQUEST);
        inOrder.verify(paymentClient).pay(1L, 9000L);
        inOrder.verify(orderWriter).confirm(1L, 42L);
    }

    @Test
    @DisplayName("create — 결제가 실패하면 FAILED 로 남기고 예외를 그대로 올린다")
    void createFailsOnPaymentFailure() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L))
                .willThrow(new PaymentCallFailedException("결제 서비스 호출 실패", new RuntimeException()));
        given(orderWriter.fail(1L)).willReturn(order(1L, Order::fail));

        assertThatThrownBy(() -> orderService.create(REQUEST))
                .isInstanceOf(PaymentCallFailedException.class);

        verify(orderWriter).fail(1L);
        verify(orderWriter, never()).confirm(anyLong(), anyLong());
    }

    @Test
    @DisplayName("create — 성공 경로에 order.id·order.status·payment.id 가 붙고 전이 event 가 2건 남는다")
    void createTagsSpanOnSuccess() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L)).willReturn(new PaymentResult(42L, "COMPLETED"));
        given(orderWriter.confirm(1L, 42L)).willReturn(order(1L, o -> o.confirmPayment(42L)));

        SpanData data = recordWithin(() -> orderService.create(REQUEST));

        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_ID)).isEqualTo(1L);
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("CONFIRMED");
        assertThat(data.getAttributes().get(DomainSpanAttributes.PAYMENT_ID)).isEqualTo(42L);
        assertThat(data.getEvents()).extracting(e -> e.getAttributes().get(DomainSpanAttributes.ORDER_STATUS))
                .containsExactly("PENDING", "CONFIRMED");
    }

    @Test
    @DisplayName("create — 실패 경로에도 order.id 가 붙고 order.status 가 FAILED 로 남는다")
    void createTagsSpanOnFailure() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L))
                .willThrow(new PaymentCallFailedException("결제 서비스 호출 실패", new RuntimeException()));
        given(orderWriter.fail(1L)).willReturn(order(1L, Order::fail));

        SpanData data = recordWithin(() -> {
            try {
                orderService.create(REQUEST);
            } catch (PaymentCallFailedException ignored) {
                // 예외 전파는 다른 테스트에서 검증한다
            }
        });

        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_ID)).isEqualTo(1L);
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("FAILED");
        assertThat(data.getAttributes().get(DomainSpanAttributes.PAYMENT_ID)).isNull();
        assertThat(data.getEvents()).extracting(EventData::getName)
                .hasSize(2);
    }

    @Test
    @DisplayName("create — 결제 중 취소된 주문은 확정하지 않고, 결제만 남았다는 사실을 event 로 남긴다")
    void createRecordsOrphanedPaymentWhenOrderCancelledMidFlight() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L)).willReturn(new PaymentResult(42L, "COMPLETED"));
        given(orderWriter.confirm(1L, 42L))
                .willThrow(new InvalidOrderStatusException("대기 중인 주문만 결제를 확정할 수 있습니다. 현재 상태: CANCELLED"));

        SpanData data = recordWithin(() -> {
            try {
                orderService.create(REQUEST);
            } catch (InvalidOrderStatusException ignored) {
                // 409 전파는 컨트롤러 테스트에서 확인한다
            }
        });

        // 409 는 4xx 라 SERVER span 이 Error 로 잡히지 않는다 — 이 event 가 유일한 흔적이다
        assertThat(data.getEvents()).extracting(EventData::getName)
                .contains(DomainSpanAttributes.PAYMENT_ORPHANED);
        assertThat(data.getAttributes().get(DomainSpanAttributes.PAYMENT_ID)).isEqualTo(42L);
        verify(orderWriter, never()).fail(anyLong());
    }

    @Test
    @DisplayName("cancel — 결제가 있으면 결제 취소를 먼저 부르고 그다음 주문을 취소한다")
    void cancelCallsPaymentBeforeOrder() {
        Order confirmed = order(1L, o -> o.confirmPayment(42L));
        given(orderWriter.readCancellable(1L)).willReturn(confirmed);
        given(orderWriter.cancel(1L)).willReturn(order(1L, Order::cancel));

        orderService.cancel(1L);

        InOrder inOrder = inOrder(orderWriter, paymentClient);
        inOrder.verify(orderWriter).readCancellable(1L);
        inOrder.verify(paymentClient).cancel(42L);
        inOrder.verify(orderWriter).cancel(1L);
    }

    @Test
    @DisplayName("cancel — 결제가 없는 주문은 결제 취소를 부르지 않는다")
    void cancelSkipsPaymentWhenAbsent() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.readCancellable(1L)).willReturn(pending);
        given(orderWriter.cancel(1L)).willReturn(order(1L, Order::cancel));

        orderService.cancel(1L);

        verify(paymentClient, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("cancel — 결제 취소가 실패하면 주문을 취소하지 않는다")
    void cancelKeepsOrderWhenPaymentCancelFails() {
        Order confirmed = order(1L, o -> o.confirmPayment(42L));
        given(orderWriter.readCancellable(1L)).willReturn(confirmed);
        willThrow(new PaymentCallFailedException("취소 실패", new RuntimeException()))
                .given(paymentClient).cancel(42L);

        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(PaymentCallFailedException.class);

        verify(orderWriter, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("create — 타임아웃이면 결제 성사 여부를 알 수 없다는 event 를 남긴다")
    void createRecordsUnknownOutcomeOnTimeout() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L)).willThrow(new PaymentCallFailedException(
                "결제 서비스 호출에 실패했습니다", new ResourceAccessException("read timed out")));
        given(orderWriter.fail(1L)).willReturn(order(1L, Order::fail));

        SpanData data = recordWithin(() -> {
            try {
                orderService.create(REQUEST);
            } catch (PaymentCallFailedException ignored) {
                // 502 전파는 별도 검증 대상
            }
        });

        assertThat(data.getEvents()).extracting(EventData::getName)
                .contains(DomainSpanAttributes.PAYMENT_OUTCOME_UNKNOWN);
    }

    @Test
    @DisplayName("create — 결제 서비스가 500 을 준 경우엔 성사 여부 불명 event 를 남기지 않는다")
    void createSkipsUnknownOutcomeOnServerError() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L)).willThrow(new PaymentCallFailedException(
                "결제 서비스 호출에 실패했습니다",
                HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "err", null, null, null)));
        given(orderWriter.fail(1L)).willReturn(order(1L, Order::fail));

        SpanData data = recordWithin(() -> {
            try {
                orderService.create(REQUEST);
            } catch (PaymentCallFailedException ignored) {
                // 502 전파는 별도 검증 대상
            }
        });

        assertThat(data.getEvents()).extracting(EventData::getName)
                .doesNotContain(DomainSpanAttributes.PAYMENT_OUTCOME_UNKNOWN);
    }

    @Test
    @DisplayName("cancel — 이미 취소된 주문의 재취소는 전이가 아니라 event 를 남기지 않는다")
    void cancelAlreadyCancelledLeavesNoTransitionEvent() {
        Order cancelled = order(1L, Order::cancel);
        given(orderWriter.readCancellable(1L)).willReturn(cancelled);
        given(orderWriter.cancel(1L)).willReturn(cancelled);

        SpanData data = recordWithin(() -> orderService.cancel(1L));

        assertThat(data.getEvents()).isEmpty();
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel — 결제 취소 후 주문 취소가 막히면 결제만 취소됐다는 사실을 event 로 남긴다")
    void cancelRecordsOverCancelledPayment() {
        Order confirmed = order(1L, o -> o.confirmPayment(42L));
        given(orderWriter.readCancellable(1L)).willReturn(confirmed);
        given(orderWriter.cancel(1L))
                .willThrow(new InvalidOrderStatusException("배송이 시작된 주문은 취소할 수 없습니다. 현재 상태: SHIPPED"));

        SpanData data = recordWithin(() -> {
            try {
                orderService.cancel(1L);
            } catch (InvalidOrderStatusException ignored) {
                // 409 전파는 별도 검증 대상
            }
        });

        assertThat(data.getEvents()).extracting(EventData::getName)
                .contains(DomainSpanAttributes.PAYMENT_OVER_CANCELLED);
        verify(paymentClient).cancel(42L);
    }

    @Test
    @DisplayName("cancel — 결제가 있는 주문의 취소 경로에 payment.id 가 붙는다")
    void cancelTagsPaymentId() {
        Order confirmed = order(1L, o -> o.confirmPayment(42L));
        given(orderWriter.readCancellable(1L)).willReturn(confirmed);
        given(orderWriter.cancel(1L)).willReturn(order(1L, Order::cancel));

        SpanData data = recordWithin(() -> orderService.cancel(1L));

        assertThat(data.getAttributes().get(DomainSpanAttributes.PAYMENT_ID)).isEqualTo(42L);
        assertThat(data.getAttributes().get(DomainSpanAttributes.ORDER_STATUS)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel — 취소 대상 조회는 전이가 아니라 event 를 남기지 않는다 (CANCELLED 1건만)")
    void cancelLeavesSingleTransitionEvent() {
        Order confirmed = order(1L, o -> o.confirmPayment(42L));
        given(orderWriter.readCancellable(1L)).willReturn(confirmed);
        given(orderWriter.cancel(1L)).willReturn(order(1L, Order::cancel));

        SpanData data = recordWithin(() -> orderService.cancel(1L));

        assertThat(data.getEvents()).extracting(e -> e.getAttributes().get(DomainSpanAttributes.ORDER_STATUS))
                .containsExactly("CANCELLED");
    }

    @Test
    @DisplayName("create — 결제 실패 기록마저 실패해도 원래 결제 실패 원인을 잃지 않는다")
    void createKeepsPaymentFailureWhenFailRecordingBreaks() {
        Order pending = order(1L, o -> {
        });
        given(orderWriter.savePending(REQUEST)).willReturn(pending);
        given(paymentClient.pay(1L, 9000L))
                .willThrow(new PaymentCallFailedException("결제 서비스 호출 실패", new RuntimeException()));
        given(orderWriter.fail(1L)).willThrow(new IllegalStateException("DB 커넥션 없음"));

        assertThatThrownBy(() -> orderService.create(REQUEST))
                .isInstanceOf(PaymentCallFailedException.class)
                .satisfies(e -> assertThat(e.getSuppressed())
                        .singleElement()
                        .isInstanceOf(IllegalStateException.class));
    }
}
