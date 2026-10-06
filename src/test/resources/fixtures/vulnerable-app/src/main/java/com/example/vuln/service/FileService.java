package com.example.vuln.service;

import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

@Service
public class FileService {

    private static final String BASE_DIR = "/var/data";

    public byte[] read(String name) throws IOException {
        return Files.readAllBytes(Paths.get(BASE_DIR, name));
    }

    public Object load(String name) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(BASE_DIR + "/" + name))) {
            return in.readObject();
        }
    }
}
