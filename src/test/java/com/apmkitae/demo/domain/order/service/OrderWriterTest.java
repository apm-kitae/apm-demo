package com.apmkitae.demo.domain.order.service;

import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import com.apmkitae.demo.domain.order.repository.OrderRepository;
import com.apmkitae.demo.global.exception.InvalidOrderStatusException;
import com.apmkitae.demo.global.exception.OrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기본적으로 클래스에 @Transactional 을 붙이지 않는다.
 * 붙이면 모든 호출이 하나의 트랜잭션에 묶여 커밋 경계가 사라진다.
 * <p>
 * 전파 속성을 검증하는 메서드만 @Transactional 을 걸고 TestTransaction 으로 바깥 트랜잭션을 직접 제어한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderWriterTest {

    @Autowired
    private OrderWriter orderWriter;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    private OrderCreateRequest request() {
        return new OrderCreateRequest("customer-1", "product-1", 2, 4500L);
    }

    @Test
    @DisplayName("savePending — PENDING 주문을 저장하고 총액을 단가 × 수량으로 계산한다")
    void savePending() {
        Order saved = orderWriter.savePending(request());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getTotalPrice()).isEqualTo(9000L);
        assertThat(orderRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("confirm — ID 로 재조회해 CONFIRMED·paymentId 를 DB 에 반영한다")
    void confirmPersistsUpdate() {
        Order pending = orderWriter.savePending(request());

        Order confirmed = orderWriter.confirm(pending.getId(), 42L);

        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPaymentId()).isEqualTo(42L);

        // 반환 인스턴스가 아니라 DB 를 확인한다
        Order reloaded = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reloaded.getPaymentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("fail — FAILED 를 DB 에 반영한다")
    void failPersistsUpdate() {
        Order pending = orderWriter.savePending(request());

        Order failed = orderWriter.fail(pending.getId());

        assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("cancel — CANCELLED 를 DB 에 반영한다")
    void cancelPersistsUpdate() {
        Order pending = orderWriter.savePending(request());

        Order cancelled = orderWriter.cancel(pending.getId());

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("readCancellable — 취소 가능한 주문을 반환한다")
    void readCancellableReturnsOrder() {
        Order pending = orderWriter.savePending(request());

        Order found = orderWriter.readCancellable(pending.getId());

        assertThat(found.getId()).isEqualTo(pending.getId());
    }

    @Test
    @DisplayName("readCancellable — 배송이 시작된 주문이면 상태를 바꾸기 전에 막는다")
    void readCancellableRejectsShipped() {
        Order pending = orderWriter.savePending(request());
        Order shipped = orderRepository.findById(pending.getId()).orElseThrow();
        shipped.updateStatus(OrderStatus.SHIPPED);
        orderRepository.save(shipped);

        assertThatThrownBy(() -> orderWriter.readCancellable(pending.getId()))
                .isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    @DisplayName("fail — REQUIRES_NEW 라 바깥 트랜잭션이 롤백돼도 FAILED 기록이 살아남는다")
    @Transactional
    void failSurvivesOuterRollback() {
        Long orderId = orderWriter.savePending(request()).getId();
        // 주문을 먼저 커밋한다. REQUIRES_NEW 는 별도 커넥션이라 미커밋 주문을 볼 수 없다
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        orderWriter.fail(orderId);
        // 바깥 트랜잭션을 롤백시킨다. REQUIRED 였다면 FAILED 기록도 함께 사라진다
        TestTransaction.flagForRollback();
        TestTransaction.end();

        TestTransaction.start();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FAILED);
        TestTransaction.end();
    }

    @Test
    @DisplayName("confirm — 결제 호출 중 취소된 주문은 CONFIRMED 로 덮이지 않는다")
    void confirmRejectsCancelledOrder() {
        Order pending = orderWriter.savePending(request());
        orderWriter.cancel(pending.getId());

        assertThatThrownBy(() -> orderWriter.confirm(pending.getId(), 42L))
                .isInstanceOf(InvalidOrderStatusException.class);

        Order reloaded = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloaded.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("없는 주문 ID 는 OrderNotFoundException")
    void unknownOrderId() {
        assertThatThrownBy(() -> orderWriter.confirm(9999L, 42L))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orderWriter.fail(9999L))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orderWriter.cancel(9999L))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orderWriter.readCancellable(9999L))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
