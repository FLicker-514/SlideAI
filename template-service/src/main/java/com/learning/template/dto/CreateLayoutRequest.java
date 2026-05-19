package com.learning.template.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateLayoutRequest {
    private String name;
    private String description;
    private List<String> tags;
    /** 完整 HTML 字符串（单文件可独立打开） */
    private String html;
}

