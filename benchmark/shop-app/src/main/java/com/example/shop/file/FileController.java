package com.example.shop.file;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final Path invoiceDir = Path.of("/var/shop/invoices").toAbsolutePath().normalize();
    private final Path imageDir = Path.of("/var/shop/images").toAbsolutePath().normalize();

    @GetMapping("/invoices/{name}")
    public byte[] invoice(@PathVariable String name) throws IOException {
        return Files.readAllBytes(invoiceDir.resolve(name));
    }

    @GetMapping("/images/{name}")
    public byte[] image(@PathVariable String name) throws IOException {
        Path file = imageDir.resolve(name).normalize();
        if (!file.startsWith(imageDir)) {
            throw new IllegalArgumentException("허용되지 않은 경로");
        }
        return Files.readAllBytes(file);
    }
}
