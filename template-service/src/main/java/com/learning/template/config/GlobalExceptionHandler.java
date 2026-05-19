package com.learning.template.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：预览请求（/preview）统一返回 200 + 错误 HTML，避免 500 白屏
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String ERROR_HTML = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/></head><body><p style=\"padding:1rem;\">预览加载失败，请稍后重试。</p></body></html>";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAny(HttpServletRequest request, Exception e) {
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        if (uri.contains("/preview")) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Preview error for {}: {}", uri, msg, e);
            String body = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/></head><body><p style=\"padding:1rem;\">预览加载失败: " + escapeHtml(msg) + "</p></body></html>";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body(body);
        }
        log.error("Unhandled error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
