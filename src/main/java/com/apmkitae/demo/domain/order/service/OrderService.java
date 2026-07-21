package com.apmkitae.demo.domain.order.service;

import com.apmkitae.demo.domain.order.client.PaymentClient;
import com.apmkitae.demo.domain.order.client.dto.PaymentResult;
import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.dto.OrderResponse;
import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import com.apmkitae.demo.domain.order.repository.OrderRepository;
import com.apmkitae.demo.global.exception.InvalidOrderStatusException;
import com.apmkitae.demo.global.exception.OrderNotFoundException;
import com.apmkitae.demo.global.exception.PaymentCallFailedException;
import com.apmkitae.demo.global.observability.DomainSpans;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

/**
 * 클래스 레벨 {@code @Transactional} 을 두지 않는다.
 * {@code create}/{@code cancel} 이 결제 서비스를 HTTP 로 호출하는데, 트랜잭션 안에서 호출하면
 * 응답을 기다리는 동안(최대 3초) DB 커넥션을 점유해 커넥션 풀이 고갈된다.
 * 트랜잭션 단위는 {@link OrderWriter} 가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderWriter orderWriter;
    private final PaymentClient paymentClient;

    /**
     * 주문을 PENDING 으로 저장·커밋한 뒤 결제를 호출하고, 결과에 따라 CONFIRMED 또는 FAILED 로 갱신한다.
     * <p>
     * 저장을 먼저 커밋하는 이유는 두 가지다 — 결제 호출 동안 커넥션을 잡지 않기 위해서이고,
     * 결제가 실패해도 주문 행이 롤백으로 사라지지 않아 FAILED 기록이 남기 때문이다.
     */
    public OrderResponse create(OrderCreateRequest request) {
        Order pending = orderWriter.savePending(request);
        DomainSpans.tagOrderTransition(pending);
        try {
            PaymentResult result = paymentClient.pay(pending.getId(), pending.getTotalPrice());
            DomainSpans.tagPayment(result.id());
            return OrderResponse.from(confirmPaid(pending.getId(), result.id()));
        } catch (PaymentCallFailedException e) {
            // 타임아웃은 요청이 전달되지 않은 것과 결제가 커밋된 뒤 응답만 못 받은 것을 구분하지 못한다.
            // 후자면 결제는 살아 있는데 주문만 FAILED 로 끝나므로, 그 가능성을 트레이스에 남긴다.
            if (e.getCause() instanceof ResourceAccessException) {
                DomainSpans.recordUnknownPaymentOutcome(pending.getId());
            }
            // 실패 기록이 또 실패해도 원래 원인을 잃지 않아야 한다.
            // e 가 사라지면 응답이 502 대신 500 이 되고, recordException 도 타지 않아
            // "왜 실패했는가"가 트레이스에서 통째로 비어 버린다.
            try {
                DomainSpans.tagOrderTransition(orderWriter.fail(pending.getId()));
            } catch (RuntimeException failToRecord) {
                e.addSuppressed(failToRecord);
            }
            throw e;
        }
    }

    /**
     * 결제 호출 구간(최대 3초) 사이에 주문이 취소됐으면 {@code confirm} 이
     * {@link InvalidOrderStatusException} 을 던져 409 로 나간다.
     * <p>
     * 4xx 라 Agent 가 SERVER span 을 Error 로 기록하지 않으므로, 결제만 남은 주문이 생겼다는 사실을
     * event 로 직접 남긴다 — 남기지 않으면 트레이스에서 이 상황을 찾을 방법이 없다.
     */
    private Order confirmPaid(Long orderId, Long paymentId) {
        try {
            Order confirmed = orderWriter.confirm(orderId, paymentId);
            DomainSpans.tagOrderTransition(confirmed);
            return confirmed;
        } catch (InvalidOrderStatusException e) {
            DomainSpans.recordOrphanedPayment(orderId, paymentId);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return OrderResponse.from(orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findOrders(String customerId) {
        List<Order> orders = (customerId == null || customerId.isBlank())
                ? orderRepository.findAll()
                : orderRepository.findByCustomerId(customerId);
        return orders.stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * 결제 취소를 주문 취소보다 먼저 호출한다. 순서를 뒤집으면 결제 취소가 실패했을 때
     * 주문만 CANCELLED 이고 결제는 COMPLETED 로 남아 돈이 돌아가지 않는다.
     */
    public OrderResponse cancel(Long id) {
        Order target = orderWriter.readCancellable(id);
        DomainSpans.tagOrderState(target);   // 조회일 뿐 전이가 아니라 event 를 남기지 않는다

        Long paymentId = target.getPaymentId();
        if (paymentId != null) {
            DomainSpans.tagPayment(paymentId);
            paymentClient.cancel(paymentId);
        }

        boolean alreadyCancelled = target.getStatus() == OrderStatus.CANCELLED;
        return OrderResponse.from(cancelOrder(id, paymentId, alreadyCancelled));
    }

    /**
     * 결제 취소와 주문 취소 사이에 다른 요청이 상태를 SHIPPED 로 바꾸면
     * {@code cancel} 이 {@link InvalidOrderStatusException} 을 던져 409 로 나간다.
     * <p>
     * 결제만 취소되고 주문은 남는 상태이며, 4xx 라 SERVER span 이 Error 로 잡히지 않는다 —
     * {@code confirmPaid} 와 같은 이유로 event 를 직접 남긴다.
     */
    private Order cancelOrder(Long orderId, Long paymentId, boolean alreadyCancelled) {
        try {
            Order cancelled = orderWriter.cancel(orderId);
            // 이미 CANCELLED 인 주문의 재취소는 멱등 허용이지만 전이가 아니다.
            // event 를 남기면 일어나지 않은 전이가 이력에 섞인다.
            if (alreadyCancelled) {
                DomainSpans.tagOrderState(cancelled);
            } else {
                DomainSpans.tagOrderTransition(cancelled);
            }
            return cancelled;
        } catch (InvalidOrderStatusException e) {
            if (paymentId != null) {
                DomainSpans.recordOverCancelledPayment(orderId, paymentId);
            }
            throw e;
        }
    }

}
