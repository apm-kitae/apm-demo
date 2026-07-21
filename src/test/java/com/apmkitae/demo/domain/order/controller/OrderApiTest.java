package com.apmkitae.demo.domain.order.controller;

import com.apmkitae.demo.domain.order.client.PaymentClient;
import com.apmkitae.demo.domain.order.client.dto.PaymentResult;
import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import com.apmkitae.demo.domain.order.repository.OrderRepository;
import com.apmkitae.demo.global.exception.PaymentCallFailedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentClient 를 목으로 대체한다. 실제 빈이면 결제 서버로 소켓을 열어
 * apm-payment 기동 여부에 따라 결과가 달라지고, 떠 있으면 테스트가 실제 결제를 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/orders — 결제가 성공하면 201과 CONFIRMED·결제 ID를 반환한다")
    void createOrder() throws Exception {
        given(paymentClient.pay(anyLong(), anyLong())).willReturn(new PaymentResult(42L, "COMPLETED"));
        OrderCreateRequest request = new OrderCreateRequest("customer-1", "product-1", 2, 4500L);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.customerId").value("customer-1"))
                .andExpect(jsonPath("$.productId").value("product-1"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.totalPrice").value(9000))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(42));
    }

    @Test
    @DisplayName("POST /api/orders — 결제 서비스 호출이 실패하면 502와 에러 메시지를 반환하고 주문은 FAILED로 남는다")
    void createOrderReturns502WhenPaymentFails() throws Exception {
        given(paymentClient.pay(anyLong(), anyLong()))
                .willThrow(new PaymentCallFailedException("결제 서비스 호출에 실패했습니다", new RuntimeException()));
        OrderCreateRequest request = new OrderCreateRequest("customer-1", "product-1", 2, 4500L);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // 결제 실패에도 주문 행은 남아야 한다 — 단일 트랜잭션이면 롤백돼 사라진다
        assertThat(orderRepository.findAll()).singleElement()
                .satisfies(order -> {
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
                    assertThat(order.getPaymentId()).isNull();
                });
    }

    @Test
    @DisplayName("POST /api/orders — 고객 ID가 비어 있으면 400을 반환한다")
    void createOrderWithBlankCustomerId() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest("", "product-1", 2, 4500L);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/orders — 수량이 1 미만이면 400을 반환한다")
    void createOrderWithInvalidQuantity() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest("customer-1", "product-1", 0, 4500L);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/orders — 단가가 1 미만이면 400을 반환한다")
    void createOrderWithInvalidUnitPrice() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest("customer-1", "product-1", 2, 0L);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/orders/{id} — 존재하는 주문을 조회하면 200을 반환한다")
    void getOrder() throws Exception {
        Order saved = orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));

        mockMvc.perform(get("/api/orders/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.customerId").value("customer-1"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} — 존재하지 않는 주문이면 404를 반환한다")
    void getOrderNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/orders?customerId= — 해당 고객의 주문만 반환한다")
    void getOrdersByCustomerId() throws Exception {
        orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));
        orderRepository.save(Order.create("customer-1", "product-2", 2, 9000L));
        orderRepository.save(Order.create("customer-2", "product-1", 3, 13500L));

        mockMvc.perform(get("/api/orders").param("customerId", "customer-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/orders — 파라미터가 없으면 전체 주문을 반환한다")
    void getAllOrders() throws Exception {
        orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));
        orderRepository.save(Order.create("customer-2", "product-1", 3, 13500L));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("POST /api/orders/{id}/cancel — 결제가 없는 주문을 취소하면 결제 취소를 부르지 않는다")
    void cancelOrder() throws Exception {
        Order saved = orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));

        mockMvc.perform(post("/api/orders/{id}/cancel", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Order cancelled = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentClient, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("POST /api/orders/{id}/cancel — 결제가 있는 주문은 결제 취소도 호출한다")
    void cancelOrderWithPayment() throws Exception {
        Order paid = Order.create("customer-1", "product-1", 1, 4500L);
        paid.confirmPayment(42L);
        Order saved = orderRepository.save(paid);

        mockMvc.perform(post("/api/orders/{id}/cancel", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(paymentClient).cancel(42L);
    }

    @Test
    @DisplayName("POST /api/orders/{id}/cancel — 배송 중인 주문을 취소하면 409를 반환한다")
    void cancelShippedOrderReturns409() throws Exception {
        Order saved = orderRepository.save(Order.create("customer-1", "product-1", 1, 4500L));
        saved.updateStatus(OrderStatus.SHIPPED);
        orderRepository.save(saved);

        mockMvc.perform(post("/api/orders/{id}/cancel", saved.getId()))
                .andExpect(status().isConflict());

        Order unchanged = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }
}
