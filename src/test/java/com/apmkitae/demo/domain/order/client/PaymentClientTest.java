package com.apmkitae.demo.domain.order.client;

import com.apmkitae.demo.domain.order.client.dto.PaymentResult;
import com.apmkitae.demo.global.exception.PaymentCallFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentClientTest {

    private static final String BASE_URL = "http://payment-test";

    private MockRestServiceServer server;
    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        paymentClient = new PaymentClient(builder);
    }

    @Test
    @DisplayName("pay — 결제 생성 응답의 id·status 를 매핑한다")
    void payMapsResponse() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.amount").value(9000))
                .andRespond(withSuccess("""
                        {"id":42,"orderId":1,"amount":9000,"status":"COMPLETED","createdAt":"2026-07-21T10:00:00"}
                        """, MediaType.APPLICATION_JSON));

        PaymentResult result = paymentClient.pay(1L, 9000L);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo("COMPLETED");
        server.verify();
    }

    @Test
    @DisplayName("pay — 500 이면 PaymentCallFailedException")
    void payWrapsServerError() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"message\":\"결제 승인에 실패했습니다\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("pay — 400 도 PaymentCallFailedException 으로 감싼다 (호출 실패 규칙 통일)")
    void payWrapsClientError() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("pay — 타임아웃·연결 실패(ResourceAccessException)도 PaymentCallFailedException")
    void payWrapsIoError() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(request -> {
                    throw new ResourceAccessException("read timed out");
                });

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("pay — 본문 변환에 실패해도 PaymentCallFailedException 으로 감싼다")
    void payWrapsBodyConversionError() {
        // 프록시가 앞에 있거나 오류 페이지를 200 으로 돌려주는 상황.
        // RestClientException 계열이라 상태 코드 예외만 잡으면 그대로 새어나가 주문이 PENDING 으로 고착된다
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("pay — 응답 본문이 비어 있으면 PaymentCallFailedException (호출부 NPE 방지)")
    void payRejectsEmptyBody() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("pay — 응답에 결제 ID 가 없으면 PaymentCallFailedException")
    void payRejectsMissingPaymentId() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("pay — COMPLETED 가 아닌 상태로 오면 PaymentCallFailedException (계약을 코드로 고정)")
    void payRejectsNonCompletedStatus() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withSuccess("{\"id\":42,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> paymentClient.pay(1L, 9000L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("cancel — 결제 취소를 호출한다")
    void cancelCallsPaymentService() {
        server.expect(requestTo(BASE_URL + "/api/payments/42/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(""))
                .andRespond(withSuccess("""
                        {"id":42,"orderId":1,"amount":9000,"status":"CANCELLED","createdAt":"2026-07-21T10:00:00"}
                        """, MediaType.APPLICATION_JSON));

        assertThatCode(() -> paymentClient.cancel(42L)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    @DisplayName("cancel — 404(결제 없음)는 취소 목적이 달성된 상태라 통과시킨다")
    void cancelIgnoresNotFound() {
        server.expect(requestTo(BASE_URL + "/api/payments/42/cancel"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> paymentClient.cancel(42L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cancel — 409(이미 취소됨)도 통과시킨다")
    void cancelIgnoresConflict() {
        server.expect(requestTo(BASE_URL + "/api/payments/42/cancel"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatCode(() -> paymentClient.cancel(42L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cancel — 404·409 외의 4xx 는 전파한다 (삼키면 결제는 살아 있는데 주문만 취소된다)")
    void cancelPropagatesOtherClientErrors() {
        server.expect(requestTo(BASE_URL + "/api/payments/42/cancel"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> paymentClient.cancel(42L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("cancel — 5xx 는 전파한다")
    void cancelPropagatesServerError() {
        server.expect(requestTo(BASE_URL + "/api/payments/42/cancel"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> paymentClient.cancel(42L))
                .isInstanceOf(PaymentCallFailedException.class);
    }

    @Test
    @DisplayName("cancel — 타임아웃도 전파한다")
    void cancelPropagatesIoError() {
        server.expect(requestTo(BASE_URL + "/api/payments/42/cancel"))
                .andRespond(request -> {
                    throw new ResourceAccessException("read timed out");
                });

        assertThatThrownBy(() -> paymentClient.cancel(42L))
                .isInstanceOf(PaymentCallFailedException.class);
    }
}
