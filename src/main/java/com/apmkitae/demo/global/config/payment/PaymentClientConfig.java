package com.apmkitae.demo.global.config.payment;

import com.apmkitae.demo.domain.order.client.PaymentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class PaymentClientConfig {

    /**
     * {@code RestClient.Builder} 타입의 빈은 선언하지 않는다.
     * Boot 의 {@code RestClientAutoConfiguration.restClientBuilder} 는 {@code @ConditionalOnMissingBean}(타입 기준)이라,
     * 결제 전용 Builder 를 빈으로 노출하면 자동설정이 백오프해 baseUrl 이 결제 서버로 고정된 싱글턴이
     * 앱 전역의 유일한 Builder 가 된다 — 다음 HTTP 클라이언트를 추가하는 순간 결제 서버로 나간다.
     * 대신 자동설정 Builder(prototype)를 주입받아 여기서만 설정한다.
     * <p>
     * request factory 를 {@code JdkClientHttpRequestFactory} 로 명시하는 이유:
     * {@code ClientHttpRequestFactories.get(settings)} 단일 인자 오버로드는
     * Apache HttpClient5 → Jetty → OkHttp → SimpleClientHttpRequestFactory 순으로 고르는데,
     * 이 프로젝트는 클래스패스에 HTTP 클라이언트가 없어 HttpURLConnection(커넥션 풀 없음)으로 내려간다.
     * 타임아웃을 넣으려다 전송 계층이 오히려 구형으로 바뀌는 셈이라 클래스를 지정한다.
     */
    @Bean
    public PaymentClient paymentClient(
            RestClient.Builder builder,
            @Value("${app.payment.base-url}") String baseUrl,
            @Value("${app.payment.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.payment.read-timeout-ms}") long readTimeoutMs) {

        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));

        return new PaymentClient(builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(JdkClientHttpRequestFactory.class, settings)));
    }
}
