package com.apmkitae.demo.global.exception;

/**
 * 결제 서비스(apm-payment) 호출이 실패했을 때 던진다.
 * <p>
 * apm-payment 의 {@code PaymentFailedException} 은 "결제 승인 실패"이고,
 * 이쪽은 "결제 서비스를 부르는 데 실패"라 의미가 다르다 — 5xx·타임아웃·연결 실패·예상 밖 4xx 를 모두 포함한다.
 */
public class PaymentCallFailedException extends RuntimeException {

    public PaymentCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
