package com.example.vuln.web;

import com.example.vuln.service.CommandService;
import com.example.vuln.service.FileService;
import com.example.vuln.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final CommandService commandService;
    private final FileService fileService;

    public UserController(UserService userService, CommandService commandService, FileService fileService) {
        this.userService = userService;
        this.commandService = commandService;
        this.fileService = fileService;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String name) {
        return userService.search(name);
    }

    @PostMapping(value = "/ping")
    public String ping(@RequestParam String host) throws Exception {
        return commandService.ping(host);
    }

    @RequestMapping(path = "/files/{name}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public byte[] download(@PathVariable String name) throws Exception {
        return fileService.read(name);
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
