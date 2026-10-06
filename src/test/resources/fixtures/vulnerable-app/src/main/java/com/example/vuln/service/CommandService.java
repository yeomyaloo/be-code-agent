package com.example.vuln.service;

import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class CommandService {

    public String ping(String host) throws IOException {
        Process process = Runtime.getRuntime().exec("ping -c 1 " + host);
        return String.valueOf(process.pid());
    }

    public Process shell(String command) throws IOException {
        return new ProcessBuilder("sh", "-c", command).start();
    }
}
