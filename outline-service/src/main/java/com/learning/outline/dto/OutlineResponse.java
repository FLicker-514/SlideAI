package com.learning.outline.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大纲接口返回：content 为大纲 JSON 字符串，与前端约定一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutlineResponse {
    private String content;
}
