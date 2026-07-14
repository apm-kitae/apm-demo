package com.apmkitae.demo.domain.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "주문 생성 요청")
public record OrderCreateRequest(
        @Schema(description = "고객 ID", example = "customer-1")
        @NotBlank(message = "고객 ID는 필수입니다")
        String customerId,

        @Schema(description = "상품 ID", example = "product-1")
        @NotBlank(message = "상품 ID는 필수입니다")
        String productId,

        @Schema(description = "주문 수량", example = "2")
        @NotNull(message = "수량은 필수입니다")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다")
        Integer quantity,

        @Schema(description = "상품 단가 (원)", example = "4500")
        @NotNull(message = "단가는 필수입니다")
        @Min(value = 1, message = "단가는 1 이상이어야 합니다")
        Long unitPrice
) {
}
