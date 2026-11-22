package com.example.shop.product;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProductQueryService {

    private static final Set<String> SORT_COLUMNS = Set.of("name", "price", "created_at");

    private final JdbcTemplate jdbcTemplate;

    public ProductQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> search(String keyword) {
        String sql = "select id, name, price from product where name like '%" + keyword + "%'";
        return jdbcTemplate.queryForList(sql);
    }

    public Map<String, Object> get(long id) {
        return jdbcTemplate.queryForMap("select id, name, price from product where id = ?", id);
    }

    public List<Map<String, Object>> listSorted(String sort) {
        if (!SORT_COLUMNS.contains(sort)) {
            throw new IllegalArgumentException("정렬할 수 없는 열: " + sort);
        }
        return jdbcTemplate.queryForList("select id, name, price from product order by " + sort);
    }
}
