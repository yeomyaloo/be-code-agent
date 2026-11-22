package com.example.shop.webhook;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Map<String, String> PARTNERS = Map.of(
            "courier", "https://courier.example.com/health",
            "payment", "https://payment.example.com/health");

    private final RestTemplate restTemplate = new RestTemplate();

    public record WebhookTest(String url) {
    }

    @PostMapping("/test")
    public String test(@RequestBody WebhookTest request) {
        return restTemplate.getForObject(request.url(), String.class);
    }

    @PostMapping("/partners")
    public String partner(@RequestParam String partner) {
        String url = PARTNERS.get(partner);
        if (url == null) {
            throw new IllegalArgumentException("모르는 협력사: " + partner);
        }
        return restTemplate.getForObject(url, String.class);
    }
}
