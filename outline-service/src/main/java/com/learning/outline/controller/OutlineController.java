package com.learning.outline.controller;

import com.learning.common.Result;
import com.learning.outline.dto.OutlineRequest;
import com.learning.outline.dto.OutlineResponse;
import com.learning.outline.service.OutlinePythonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大纲服务 REST 接口（Java）；仅与 AI 相关的逻辑由 Python 执行。
 */
@Slf4j
@RestController
@RequestMapping("/outline")
@RequiredArgsConstructor
public class OutlineController {

    private final OutlinePythonService outlinePythonService;

    @PostMapping
    public Result<OutlineResponse> generateOutline(@RequestBody OutlineRequest request) {
        if (request == null || !StringUtils.hasText(request.getTopic())) {
            return Result.error(400, "主题不能为空");
        }
        try {
            String content = outlinePythonService.generateOutline(request);
            return Result.success(OutlineResponse.builder().content(content).build());
        } catch (Exception e) {
            log.warn("大纲生成失败", e);
            return Result.error(500, e.getMessage() != null ? e.getMessage() : "大纲生成失败");
        }
    }
}
