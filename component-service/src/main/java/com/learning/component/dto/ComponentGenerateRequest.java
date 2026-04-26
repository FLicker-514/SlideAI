package com.learning.component.dto;

import lombok.Data;

@Data
public class ComponentGenerateRequest {
    /** 组件描述（给 LLM） */
    private String description;
}

