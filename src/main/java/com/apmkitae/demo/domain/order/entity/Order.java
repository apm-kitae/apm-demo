package com.apmkitae.demo.domain.order.entity;

import com.apmkitae.demo.global.exception.InvalidOrderStatusException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String customerId;

    @Column(nullable = false, length = 50)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Long totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    /** 결제 서비스(apm-payment)가 발급한 결제 ID. 결제 전이거나 실패한 주문은 비어 있다 */
    @Column
    private Long paymentId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Order(String customerId, String productId, Integer quantity, Long totalPrice) {
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.status = OrderStatus.PENDING;
    }

    public static Order create(String customerId, String productId, Integer quantity, Long totalPrice) {
        return new Order(customerId, productId, quantity, totalPrice);
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void updateStatus(OrderStatus status) {
        this.status = status;
    }

    /**
     * PENDING 인 주문만 확정한다.
     * <p>
     * 결제 호출 구간(최대 3초) 사이에 취소가 들어와 CANCELLED 로 커밋됐을 수 있다.
     * 상태를 보지 않고 덮어쓰면 취소 200 을 이미 응답한 주문이 CONFIRMED 로 되살아난다.
     * <p>
     * 다만 이 검사는 애플리케이션 레벨 read-then-write 이고 조회에 락이 없다.
     * {@code confirm} 트랜잭션이 PENDING 을 읽은 <b>뒤</b> 취소가 커밋되는 ms 단위 구간은 막지 못한다 —
     * 낙관적 락({@code @Version})은 이 프로젝트 범위 밖이다. 초 단위인 결제 호출 구간만 막는 것이 목적이다.
     */
    public void confirmPayment(Long paymentId) {
        requirePending("결제를 확정");
        this.status = OrderStatus.CONFIRMED;
        this.paymentId = paymentId;
    }

    /** {@link #confirmPayment} 와 같은 이유로 PENDING 인 주문만 실패 처리한다 */
    public void fail() {
        requirePending("결제 실패를 기록");
        this.status = OrderStatus.FAILED;
    }

    private void requirePending(String action) {
        if (this.status != OrderStatus.PENDING) {
            throw new InvalidOrderStatusException(
                    "대기 중인 주문만 " + action + "할 수 있습니다. 현재 상태: " + this.status);
        }
    }

    /**
     * 취소 가능 여부만 확인한다. 결제 취소를 먼저 호출해야 하므로
     * 주문 상태를 바꾸기 전에 별도 트랜잭션에서 검증할 수단이 필요하다.
     */
    public void validateCancellable() {
        if (this.status == OrderStatus.SHIPPED || this.status == OrderStatus.DELIVERED) {
            throw new InvalidOrderStatusException("배송이 시작된 주문은 취소할 수 없습니다. 현재 상태: " + this.status);
        }
    }

    public void cancel() {
        validateCancellable();
        this.status = OrderStatus.CANCELLED;
    }
}
