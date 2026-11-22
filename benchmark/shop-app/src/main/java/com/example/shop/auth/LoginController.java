package com.example.shop.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final ResetTokenService resetTokens;

    public LoginController(ResetTokenService resetTokens) {
        this.resetTokens = resetTokens;
    }

    @GetMapping("/done")
    public void done(@RequestParam String next, HttpServletResponse response) throws IOException {
        response.sendRedirect(next);
    }

    @GetMapping("/logout")
    public void logout(HttpServletResponse response) throws IOException {
        response.sendRedirect("/");
    }

    @PostMapping("/password-reset")
    public String passwordReset(@RequestParam String email) {
        return resetTokens.issue(email);
    }

    @GetMapping("/banner")
    public String banner() {
        return resetTokens.pickBanner();
    }
}
