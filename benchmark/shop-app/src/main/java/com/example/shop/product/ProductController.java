package com.example.shop.product;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductQueryService products;

    public ProductController(ProductQueryService products) {
        this.products = products;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q) {
        return products.search(q);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return products.get(id);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "name") String sort) {
        return products.listSorted(sort);
    }
}
