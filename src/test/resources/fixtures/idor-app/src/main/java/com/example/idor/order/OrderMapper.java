package com.example.idor.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {

    /** MyBatis: id 하나로 조회 (IDOR 후보) */
    Order selectById(Long id);

    /** MyBatis: id 하나로 삭제 (IDOR 후보) */
    int deleteOrder(@Param("orderId") Long orderId);

    /** MyBatis: 주인 조건이 붙은 조회 (후보 아님) */
    Order selectMine(@Param("id") Long id, @Param("buyerId") Long buyerId);
}
