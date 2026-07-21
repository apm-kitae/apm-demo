package com.apmkitae.demo.domain.order.service;

import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.repository.OrderRepository;
import com.apmkitae.demo.global.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 트랜잭션 단위. {@code OrderService} 는 트랜잭션 밖에서 결제 서비스를 호출해야 하므로
 * 상태 변경 구간만 여기로 분리한다.
 * <p>
 * 별도 빈인 이유: 같은 클래스 안의 메서드를 호출하면 프록시를 타지 않아 {@code @Transactional} 이 적용되지 않는다.
 * <p>
 * 모든 메서드가 엔티티가 아니라 <b>ID</b> 를 받는 이유: {@code open-in-view: false} 라 커밋 후 반환된 엔티티는
 * detached 다. detached 인스턴스를 고쳐도 dirty checking 이 없어 UPDATE 가 나가지 않으므로,
 * 트랜잭션 안에서 재조회한 뒤 변경하고 명시적으로 저장한다.
 */
@Component
@RequiredArgsConstructor
public class OrderWriter {

    private final OrderRepository orderRepository;

    @Transactional
    public Order savePending(OrderCreateRequest request) {
        long totalPrice = request.unitPrice() * request.quantity();
        Order order = Order.create(request.customerId(), request.productId(), request.quantity(), totalPrice);
        return orderRepository.save(order);
    }

    @Transactional
    public Order confirm(Long orderId, Long paymentId) {
        Order order = getOrder(orderId);
        order.confirmPayment(paymentId);
        return orderRepository.save(order);
    }

    /**
     * 상위에 트랜잭션이 걸려 있어도 FAILED 기록이 롤백되지 않도록 새 트랜잭션에서 실행한다.
     * 호출자가 곧바로 예외를 다시 던지기 때문에, 참여 방식이면 실패 기록까지 함께 사라진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order fail(Long orderId) {
        Order order = getOrder(orderId);
        order.fail();
        return orderRepository.save(order);
    }

    /**
     * 결제 취소를 먼저 호출해야 하므로, 주문 상태를 바꾸기 전에 취소 가능 여부만 확인한다.
     */
    @Transactional(readOnly = true)
    public Order readCancellable(Long orderId) {
        Order order = getOrder(orderId);
        order.validateCancellable();
        return order;
    }

    @Transactional
    public Order cancel(Long orderId) {
        Order order = getOrder(orderId);
        order.cancel();
        return orderRepository.save(order);
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
