package com.cashfactories.formula_one_future_oracle.controller;

import com.cashfactories.formula_one_future_oracle.service.RssParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final RssParserService rssParserService;

    // GET /api/news/refresh/1 — принудительно скачать новости для GP с ID 1
    @GetMapping("/refresh/{gpId}")
    public ResponseEntity<String> refreshNews(@PathVariable Long gpId) {
        rssParserService.fetchAndProcessNews(gpId);
        return ResponseEntity.ok("Новости успешно обновлены для GP ID: " + gpId);
    }
}