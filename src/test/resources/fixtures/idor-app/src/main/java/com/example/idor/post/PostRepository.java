package com.example.idor.post;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** 주인 조건이 붙은 조회 (IDOR 후보가 아님) */
    Optional<Post> findByIdAndWriterId(Long id, Long writerId);
}
