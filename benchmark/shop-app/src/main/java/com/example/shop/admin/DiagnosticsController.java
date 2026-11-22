package com.example.shop.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin")
public class DiagnosticsController {

    @PostMapping("/ping")
    public String ping(@RequestParam String host) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("ping -c 1 " + host);
        return process.waitFor() == 0 ? "ok" : "fail";
    }

    @GetMapping("/uptime")
    public String uptime() throws IOException {
        Process process = new ProcessBuilder("uptime").start();
        return new String(process.getInputStream().readAllBytes());
    }
}
