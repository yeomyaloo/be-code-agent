package com.example.idor.order;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public OrderController(OrderRepository orderRepository, OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable Long id) {
        return orderMapper.selectById(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        orderMapper.deleteOrder(id);
    }

    @GetMapping("/{id}/detail")
    public Order detail(@PathVariable Long id) {
        return orderRepository.findDetail(id).orElseThrow();
    }

    @GetMapping
    public List<Order> list(@RequestParam Long buyerId) {
        return orderRepository.findByBuyerId(buyerId);
    }

    /** 요청 본문의 주문(id 포함)을 그대로 저장한다. 남의 주문 id 를 넣으면 덮어쓴다 */
    @PutMapping
    public Order update(@RequestBody Order order) {
        return orderRepository.save(order);
    }

    @GetMapping("/mine/{id}")
    public Order mine(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId) {
        orderMapper.selectMine(id, userId);
        return orderRepository.findMine(id, userId).orElseThrow();
    }

    @GetMapping("/search")
    public List<Order> search(@RequestParam String address) {
        return orderRepository.findByAddress(address);
    }
}
