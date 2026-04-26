package com.learning.component.dto;

import lombok.Data;

@Data
public class LlmChatResponse {
    private String content;
    private String model;
}

