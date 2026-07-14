package com.apmkitae.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class DemoApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 정상적으로 로드된다")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("Swagger 설정 빈이 등록된다")
    void swaggerConfigLoaded() {
        assertThat(context.containsBean("swaggerConfig")).isTrue();
    }
}
