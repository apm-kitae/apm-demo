package com.apmkitae.demo.domain.order.client;

import com.apmkitae.demo.domain.order.client.dto.PaymentResult;
import com.apmkitae.demo.global.exception.PaymentCallFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 결제 서비스(apm-payment) 호출. traceparent 헤더 주입은 OTel Java Agent 가 처리하므로
 * 여기서 헤더를 다루지 않는다 — 이 호출이 만드는 CLIENT span 이 apm-payment 의 SERVER span 과 이어진다.
 * 생성자가 완성된 {@code RestClient} 가 아니라 {@code Builder} 를 받는 이유:
 * {@code MockRestServiceServer.bindTo()} 가 {@code RestClient.Builder} 만 받는다.
 */
public class PaymentClient {

    private static final String PAYMENTS_PATH = "/api/payments";
    private static final String COMPLETED = "COMPLETED";

    private final RestClient restClient;

    public PaymentClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    public PaymentResult pay(Long orderId, Long amount) {
        PaymentResult result;
        try {
            result = restClient.post()
                    .uri(PAYMENTS_PATH)
                    .body(Map.of("orderId", orderId, "amount", amount))
                    .retrieve()
                    .body(PaymentResult.class);
        } catch (RestClientException e) {
            throw new PaymentCallFailedException(
                    "결제 서비스 호출에 실패했습니다. orderId=" + orderId, e);
        }
        // body(Class) 는 @Nullable 이다. 여기서 막지 않으면 호출부가 NPE 를 내고,
        // 그 NPE 는 PaymentCallFailedException 이 아니라서 주문이 PENDING 으로 고착된다.
        if (result == null || result.id() == null) {
            throw new PaymentCallFailedException(
                    "결제 서비스 응답에 결제 ID 가 없습니다. orderId=" + orderId, null);
        }
        // apm-payment 는 2xx 면 항상 COMPLETED 를 돌려준다 (실패는 롤백돼 행이 남지 않는다).
        // 계약을 코드로 고정해 두어야 상대 서비스가 상태를 늘렸을 때 조용히 통과하지 않는다.
        if (!COMPLETED.equals(result.status())) {
            throw new PaymentCallFailedException(
                    "결제가 완료 상태가 아닙니다. orderId=" + orderId + ", status=" + result.status(), null);
        }
        return result;
    }

    /**
     * 404(결제 없음)·409(이미 취소됨)는 취소 목적이 이미 달성된 상태라 통과시킨다.
     * 그 외 4xx 를 삼키면 결제는 COMPLETED 인데 주문만 CANCELLED 가 되므로 전파한다.
     */
    public void cancel(Long paymentId) {
        try {
            restClient.post()
                    .uri(PAYMENTS_PATH + "/{paymentId}/cancel", paymentId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (isAlreadyCancelled(e)) {
                return;
            }
            throw new PaymentCallFailedException(
                    "결제 취소 호출에 실패했습니다. paymentId=" + paymentId, e);
        } catch (RestClientException e) {
            throw new PaymentCallFailedException(
                    "결제 취소 호출에 실패했습니다. paymentId=" + paymentId, e);
        }
    }

    private boolean isAlreadyCancelled(HttpClientErrorException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        return status == HttpStatus.NOT_FOUND || status == HttpStatus.CONFLICT;
    }
}
