package com.example.owasp;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class GreetingController {

    private final TokenService tokenService;

    public GreetingController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    // 이름을 이스케이프하지 않고 HTML 로 쓴다 (XSS)
    @GetMapping("/api/hello")
    public void hello(@RequestParam String name, HttpServletResponse response) throws IOException {
        response.setContentType("text/html");
        response.getWriter().write("<h1>Hello " + name + "</h1>");
    }

    @GetMapping("/api/password-reset")
    public String reset(@RequestParam String email) {
        return tokenService.resetToken(email);
    }
}
