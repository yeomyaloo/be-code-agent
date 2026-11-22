package com.example.shop.order;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;

    private Long customerId;

    private String address;

    private long totalPrice;

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getAddress() {
        return address;
    }

    public long getTotalPrice() {
        return totalPrice;
    }
}
