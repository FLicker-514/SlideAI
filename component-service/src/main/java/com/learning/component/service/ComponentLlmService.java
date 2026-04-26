package com.learning.component.service;

import com.learning.common.Result;
import com.learning.component.dto.ComponentGenerateRequest;
import com.learning.component.dto.ComponentGenerateResponse;
import com.learning.component.dto.LlmChatMessage;
import com.learning.component.dto.LlmChatRequest;
import com.learning.component.dto.LlmChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Slf4j
@Service
public class ComponentLlmService {

    private final RestTemplate restTemplate;

    @Value("${component.llm.base-url:http://localhost:8082}")
    private String llmBaseUrl;

    @Value("${component.llm.provider:qwen}")
    private String provider;

    @Value("${component.llm.prompt-key:ppt-component-html}")
    private String promptKey;

    public ComponentLlmService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ComponentGenerateResponse generate(ComponentGenerateRequest req) {
        String desc = req != null ? req.getDescription() : null;
        if (!StringUtils.hasText(desc)) {
            throw new IllegalArgumentException("description 不能为空");
        }
        String url = UriComponentsBuilder.fromHttpUrl(llmBaseUrl)
                .path("/llm/chat")
                .queryParam("provider", provider)
                .queryParam("promptKey", promptKey)
                .build()
                .toUriString();

        LlmChatRequest body = LlmChatRequest.builder()
                .messages(List.of(LlmChatMessage.builder().role("user").content(desc.trim()).build()))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LlmChatRequest> entity = new HttpEntity<>(body, headers);

        Result result = restTemplate.postForObject(url, entity, Result.class);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            String msg = result != null ? result.getMessage() : "llm-service 返回为空";
            throw new RuntimeException("LLM 生成失败: " + msg);
        }
        // data 是 LinkedHashMap，手动取 content
        Object data = result.getData();
        String content = null;
        if (data instanceof java.util.Map<?, ?> map) {
            Object c = map.get("content");
            if (c instanceof String s) content = s;
        } else if (data instanceof LlmChatResponse resp) {
            content = resp.getContent();
        }
        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("LLM 未返回 HTML 内容");
        }
        String html = stripMarkdownCodeFence(content);
        return ComponentGenerateResponse.builder().html(html).build();
    }

    private String stripMarkdownCodeFence(String s) {
        if (s == null) return "";
        String out = s.trim();
        out = out.replaceAll("^```(?:html|xml)?\\s*", "");
        out = out.replaceAll("\\s*```$", "");
        return out.trim();
    }
}

