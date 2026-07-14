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

    public void cancel() {
        if (this.status == OrderStatus.SHIPPED || this.status == OrderStatus.DELIVERED) {
            throw new InvalidOrderStatusException("배송이 시작된 주문은 취소할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = OrderStatus.CANCELLED;
    }
}
