package com.apmkitae.demo.global.exception;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code @ResponseStatus} 로 예외를 삼키면 서블릿 컨테이너까지 올라가지 않아
 * Agent 가 붙이는 exception span event 가 비어 버린다.
 * 핸들러가 직접 recordException 을 남기는지 확인한다.
 */
class GlobalExceptionHandlerTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("결제 호출 실패를 exception span event 로 남긴다 — 502의 원인을 트레이스만으로 읽을 수 있어야 한다")
    void recordsExceptionOnSpan() {
        PaymentCallFailedException exception =
                new PaymentCallFailedException("결제 서비스 호출에 실패했습니다. orderId=1", new RuntimeException("timeout"));

        Span span = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("POST /api/orders").startSpan();
        Map<String, String> body;
        try (Scope ignored = span.makeCurrent()) {
            body = handler.handlePaymentCallFailed(exception);
        } finally {
            span.end();
        }

        assertThat(body).containsEntry("message", exception.getMessage());

        List<SpanData> spans = OTEL.getSpans();
        assertThat(spans).singleElement().satisfies(data ->
                assertThat(data.getEvents()).singleElement().satisfies(event -> {
                    assertThat(event.getName()).isEqualTo("exception");
                    assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                            .isEqualTo(PaymentCallFailedException.class.getName());
                    assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.message")))
                            .isEqualTo(exception.getMessage());
                }));
    }

    @Test
    @DisplayName("Agent 미기동 경로 — 활성 span 이 없어도 예외 없이 응답을 만든다")
    void worksWithoutActiveSpan() {
        PaymentCallFailedException exception =
                new PaymentCallFailedException("결제 서비스 호출 실패", new RuntimeException());

        assertThatCode(() -> handler.handlePaymentCallFailed(exception)).doesNotThrowAnyException();
        assertThat(OTEL.getSpans()).isEmpty();
    }
}
