package com.vcube.support;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    public List<CategorySummary> list() {
        return categories.findByEnabledTrueOrderByNameAsc()
                .stream()
                .map(category -> new CategorySummary(
                        category.getCode(), category.getName(), category.getDescription()))
                .toList();
    }

    public record CategorySummary(String code, String name, String description) {}
}