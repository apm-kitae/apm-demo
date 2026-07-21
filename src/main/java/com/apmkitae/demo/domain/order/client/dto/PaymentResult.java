package com.apmkitae.demo.domain.order.client.dto;

/**
 * apm-payment 의 결제 생성 응답 중 주문이 쓰는 값만 담는다.
 * 실제 응답은 {@code {id, orderId, amount, status, createdAt}} 이고 나머지는 무시한다.
 */
public record PaymentResult(Long id, String status) {
}
