package com.apmkitae.demo.domain.order.repository;

import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("주문을 저장하고 ID로 조회한다")
    void saveAndFindById() {
        // given
        Order order = Order.create("customer-1", "product-1", 2, 9000L);

        // when
        Order saved = orderRepository.save(order);
        Order found = orderRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getCustomerId()).isEqualTo("customer-1");
        assertThat(found.getProductId()).isEqualTo("product-1");
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("고객 ID로 해당 고객의 주문만 조회한다")
    void findByCustomerId() {
        // given
        orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));
        orderRepository.save(Order.create("customer-1", "product-2", 2, 9000L));
        orderRepository.save(Order.create("customer-2", "product-1", 3, 13500L));

        // when
        List<Order> orders = orderRepository.findByCustomerId("customer-1");

        // then
        assertThat(orders).hasSize(2)
                .allSatisfy(order -> assertThat(order.getCustomerId()).isEqualTo("customer-1"));
    }

    @Test
    @DisplayName("상태로 주문을 조회한다")
    void findByStatus() {
        // given
        Order pending = orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));
        Order cancelled = orderRepository.save(Order.create("customer-2", "product-1", 1, 4500L));
        cancelled.cancel();
        orderRepository.save(cancelled);

        // when
        List<Order> cancelledOrders = orderRepository.findByStatus(OrderStatus.CANCELLED);

        // then
        assertThat(cancelledOrders).hasSize(1);
        assertThat(cancelledOrders.get(0).getId()).isEqualTo(cancelled.getId());
    }
}
