package com.apmkitae.demo.domain.order.dto;

import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "주문 응답")
public record OrderResponse(
        @Schema(description = "주문 ID", example = "1")
        Long id,

        @Schema(description = "고객 ID", example = "customer-1")
        String customerId,

        @Schema(description = "상품 ID", example = "product-1")
        String productId,

        @Schema(description = "주문 수량", example = "2")
        Integer quantity,

        @Schema(description = "총액 (단가 × 수량, 원)", example = "9000")
        Long totalPrice,

        @Schema(description = "주문 상태", example = "CONFIRMED")
        OrderStatus status,

        @Schema(description = "결제 서비스가 발급한 결제 ID. 결제 전이거나 실패한 주문은 null", example = "1")
        Long paymentId,

        @Schema(description = "주문 생성 시각")
        LocalDateTime createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getProductId(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getPaymentId(),
                order.getCreatedAt()
        );
    }
}
