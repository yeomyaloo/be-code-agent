package com.example.idor.comment;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommentController {

    private final CommentRepository commentRepository;

    public CommentController(CommentRepository commentRepository) {
        this.commentRepository = commentRepository;
    }

    @DeleteMapping("/api/comments/{id}")
    public void delete(@PathVariable Long id) {
        commentRepository.deleteById(id);
    }
}
