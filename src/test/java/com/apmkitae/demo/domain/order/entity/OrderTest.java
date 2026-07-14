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
}
