package com.example.vuln.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {

    private final JdbcTemplate jdbcTemplate;

    public UserServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Map<String, Object>> search(String name) {
        return findByName(name);
    }

    private List<Map<String, Object>> findByName(String name) {
        String sql = "select * from users where name = '" + name + "'";
        return jdbcTemplate.queryForList(sql);
    }
}
