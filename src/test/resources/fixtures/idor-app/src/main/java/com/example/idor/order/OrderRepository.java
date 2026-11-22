package com.example.idor.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 직접 쓴 쿼리로 id 하나만 받아 조회 (IDOR 후보) */
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findDetail(@Param("id") Long id);

    /** 요청에서 받은 구매자 id 로 목록 조회 (IDOR 후보) */
    List<Order> findByBuyerId(Long buyerId);

    /** 주인 조건이 붙은 조회 (후보 아님) */
    @Query("select o from Order o where o.id = :id and o.buyerId = :buyerId")
    Optional<Order> findMine(@Param("id") Long id, @Param("buyerId") Long buyerId);

    /** id 가 아닌 값으로 조회 (후보 아님) */
    List<Order> findByAddress(String address);
}
