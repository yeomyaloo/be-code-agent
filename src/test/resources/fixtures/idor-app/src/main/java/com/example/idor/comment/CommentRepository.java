package com.example.idor.comment;

import com.example.idor.common.BaseRepository;

/** 프로젝트 안의 상위 저장소 인터페이스를 거쳐 JpaRepository 를 상속한다 */
public interface CommentRepository extends BaseRepository<Comment> {
}
