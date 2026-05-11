package com.learning.outline.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 制作工坊「形成大纲」请求：与第一步（主题 + 参考文档 content.md）衔接。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutlineRequest {
    private String topic;
    private List<String> documentContents;
    private String language;
    private String extraRequirements;
}
