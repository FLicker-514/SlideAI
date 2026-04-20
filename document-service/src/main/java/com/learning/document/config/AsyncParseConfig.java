package com.learning.document.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用于 PDF 解析等耗时任务的异步执行线程池，避免长时间占用 HTTP 连接。
 */
@Configuration
public class AsyncParseConfig {

    @Bean(name = "pdfParseExecutor")
    public ExecutorService pdfParseExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
