package com.apmkitae.demo.domain.order.repository;

import com.apmkitae.demo.domain.order.entity.Order;
import com.apmkitae.demo.domain.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(OrderStatus status);
}
