package com.learning.component.dto;

import lombok.Data;

@Data
public class ComponentSaveRequest {
    private String userId;
    private String name;
    private String description;
    /** 组件 HTML 片段 */
    private String html;
}

