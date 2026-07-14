package com.apmkitae.demo.domain.order.controller;

import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.dto.OrderResponse;
import com.apmkitae.demo.domain.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Order", description = "주문 API")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "주문 생성", description = "고객 ID, 상품 ID, 수량, 단가로 주문을 생성. 총액은 서버가 단가 × 수량으로 계산")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 생성 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@Valid @RequestBody OrderCreateRequest request) {
        return orderService.create(request);
    }

    @Operation(summary = "주문 단건 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "주문 없음")
    })
    @GetMapping("/{id}")
    public OrderResponse getOrder(@Parameter(description = "주문 ID") @PathVariable Long id) {
        return orderService.findById(id);
    }

    @Operation(summary = "주문 목록 조회", description = "customerId를 주면 해당 고객의 주문만, 없으면 전체 조회")
    @GetMapping
    public List<OrderResponse> getOrders(
            @Parameter(description = "고객 ID (선택)") @RequestParam(required = false) String customerId) {
        return orderService.findOrders(customerId);
    }

    @Operation(summary = "주문 취소", description = "배송 시작 전(PENDING/CONFIRMED) 주문만 취소 가능")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "404", description = "주문 없음"),
            @ApiResponse(responseCode = "409", description = "배송 시작 이후라 취소 불가")
    })
    @PostMapping("/{id}/cancel")
    public OrderResponse cancelOrder(@Parameter(description = "주문 ID") @PathVariable Long id) {
        return orderService.cancel(id);
    }
}
