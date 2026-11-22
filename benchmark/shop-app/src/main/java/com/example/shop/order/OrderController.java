package com.example.shop.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orders;

    public OrderController(OrderRepository orders) {
        this.orders = orders;
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable Long id, @RequestHeader("X-Customer-Id") Long customerId) {
        return orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/{id}/receipt")
    public String receipt(@PathVariable Long id, @RequestHeader("X-Customer-Id") Long customerId) {
        Order order = orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!order.getCustomerId().equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "주문 " + order.getId() + " 결제 금액 " + order.getTotalPrice();
    }
}
