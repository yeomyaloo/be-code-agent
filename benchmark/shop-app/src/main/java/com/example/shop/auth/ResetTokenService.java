package com.example.shop.auth;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class ResetTokenService {

    private static final List<String> BANNERS = List.of("summer-sale", "free-shipping", "new-arrivals");

    private final Map<String, String> tokens = new HashMap<>();

    public String issue(String email) {
        Random random = new Random();
        String token = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        tokens.put(token, email);
        return "메일을 보냈습니다";
    }

    public String pickBanner() {
        Random random = new Random();
        return BANNERS.get(random.nextInt(BANNERS.size()));
    }
}
