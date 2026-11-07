package com.example.idor.post;

import org.springframework.stereotype.Service;

@Service
public class PostService {

    private final PostRepository postRepository;

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    /** 누구의 글이든 id 만으로 읽는다 */
    public Post get(Long id) {
        return postRepository.findById(id).orElseThrow();
    }

    /** 누구의 글이든 id 만으로 지운다 */
    public void delete(Long id) {
        postRepository.deleteById(id);
    }

    /** 주인 조건으로 읽는다 */
    public Post getMine(Long id, Long userId) {
        return postRepository.findByIdAndWriterId(id, userId).orElseThrow();
    }
}
