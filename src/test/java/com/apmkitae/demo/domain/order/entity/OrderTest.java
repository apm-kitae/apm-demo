package com.apmkitae.demo.domain.order.entity;

import com.apmkitae.demo.global.exception.InvalidOrderStatusException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    @DisplayName("주문을 생성하면 PENDING 상태로 시작한다")
    void createOrderWithPendingStatus() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);

        assertThat(order.getCustomerId()).isEqualTo("customer-1");
        assertThat(order.getProductId()).isEqualTo("product-1");
        assertThat(order.getQuantity()).isEqualTo(2);
        assertThat(order.getTotalPrice()).isEqualTo(9000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING 상태의 주문은 취소할 수 있다")
    void cancelPendingOrder() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("배송 중(SHIPPED)인 주문은 취소할 수 없다")
    void cannotCancelShippedOrder() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        order.updateStatus(OrderStatus.SHIPPED);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    @DisplayName("배송 완료(DELIVERED)된 주문은 취소할 수 없다")
    void cannotCancelDeliveredOrder() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        order.updateStatus(OrderStatus.DELIVERED);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    @DisplayName("주문을 생성한 시점에는 결제 ID가 없다")
    void createdOrderHasNoPaymentId() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);

        assertThat(order.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("결제를 확정하면 CONFIRMED 상태가 되고 결제 ID를 보관한다")
    void confirmPayment() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);

        order.confirmPayment(42L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("결제에 실패하면 FAILED 상태가 되고 결제 ID는 비어 있다")
    void fail() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);

        order.fail();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("결제 확정은 PENDING 주문만 가능 — 결제 호출 중 취소된 주문을 되살리지 않는다")
    void cannotConfirmCancelledOrder() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        order.cancel();

        assertThatThrownBy(() -> order.confirmPayment(42L))
                .isInstanceOf(InvalidOrderStatusException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("결제 실패 기록도 PENDING 주문만 가능 — 취소된 주문을 FAILED 로 되돌리지 않는다")
    void cannotFailCancelledOrder() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        order.cancel();

        assertThatThrownBy(order::fail)
                .isInstanceOf(InvalidOrderStatusException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소 가능 검증 — PENDING/CONFIRMED 주문은 통과한다")
    void validateCancellablePasses() {
        Order pending = Order.create("customer-1", "product-1", 2, 9000L);
        Order confirmed = Order.create("customer-1", "product-1", 2, 9000L);
        confirmed.confirmPayment(42L);

        // 예외가 나지 않는 것이 검증 대상
        pending.validateCancellable();
        confirmed.validateCancellable();
    }

    @Test
    @DisplayName("취소 가능 검증 — 배송이 시작된 주문은 cancel() 과 동일한 예외를 던진다")
    void validateCancellableRejectsShipped() {
        Order order = Order.create("customer-1", "product-1", 2, 9000L);
        order.updateStatus(OrderStatus.SHIPPED);

        assertThatThrownBy(order::validateCancellable)
                .isInstanceOf(InvalidOrderStatusException.class);
    }
}
