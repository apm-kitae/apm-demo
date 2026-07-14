package com.apmkitae.demo.domain.order.service;

import com.apmkitae.demo.domain.order.dto.OrderCreateRequest;
import com.apmkitae.demo.domain.order.dto.OrderResponse;
import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.repository.OrderRepository;
import com.apmkitae.demo.global.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse create(OrderCreateRequest request) {
        long totalPrice = request.unitPrice() * request.quantity();
        Order order = Order.create(request.customerId(), request.productId(), request.quantity(), totalPrice);
        return OrderResponse.from(orderRepository.save(order));
    }

    public OrderResponse findById(Long id) {
        return OrderResponse.from(getOrder(id));
    }

    public List<OrderResponse> findOrders(String customerId) {
        List<Order> orders = (customerId == null || customerId.isBlank())
                ? orderRepository.findAll()
                : orderRepository.findByCustomerId(customerId);
        return orders.stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = getOrder(id);
        order.cancel();
        return OrderResponse.from(order);
    }

    private Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
