package com.apmkitae.demo.global.exception;

import io.opentelemetry.api.trace.Span;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleOrderNotFound(OrderNotFoundException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(InvalidOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleInvalidOrderStatus(InvalidOrderStatusException e) {
        return Map.of("message", e.getMessage());
    }

    /**
     * 502 로 응답해야 OTel Agent 가 SERVER span 을 StatusCode=Error 로 기록한다 (HTTP semconv 상 SERVER 는 5xx 부터).
     * <p>
     * {@code @ResponseStatus} 로 예외를 삼키면 서블릿 컨테이너까지 올라가지 않아 Agent 가 붙이는
     * exception span event 와 StatusMessage 가 비어 버린다. {@code recordException} 으로 직접 남겨야
     * ClickHouse 의 {@code Events.Name='exception'} 조회로 "왜 502 인가"를 트레이스만으로 설명할 수 있다.
     */
    @ExceptionHandler(PaymentCallFailedException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handlePaymentCallFailed(PaymentCallFailedException e) {
        Span.current().recordException(e);
        return Map.of("message", e.getMessage());
    }
}
