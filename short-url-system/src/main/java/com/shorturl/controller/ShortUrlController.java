package com.shorturl.controller;

import com.shorturl.service.ShortUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ShortUrlController {

    private final ShortUrlService shortUrlService;

    /**
     * 创建短链接
     * POST /api/shorten
     * Body: {"originalUrl": "https://example.com/very/long/url"}
     */
    @PostMapping("/shorten")
    public ResponseEntity<Map<String, String>> createShortUrl(@RequestBody Map<String, String> request) {
        String originalUrl = request.get("originalUrl");

        if (originalUrl == null || originalUrl.trim().isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "originalUrl不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            String shortCode = shortUrlService.createShortUrl(originalUrl);

            Map<String, String> response = new HashMap<>();
            response.put("shortCode", shortCode);
            response.put("shortUrl", "http://localhost:8081/" + shortCode);
            response.put("originalUrl", originalUrl);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("创建短链接失败", e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "创建短链接失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 短链接跳转
     * GET /{shortCode}
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        log.info("收到跳转请求: {}", shortCode);

        String originalUrl = shortUrlService.getOriginalUrl(shortCode);

        if (originalUrl == null) {
            return ResponseEntity.notFound().build();
        }

        // 302重定向
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", originalUrl)
                .build();
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "short-url-system");
        return ResponseEntity.ok(response);
    }
}
