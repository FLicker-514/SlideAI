package com.learning.style.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * history.json 中单条风格：风格 ID、风格名称、描述标签、使用场景标签。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StyleHistoryItem {
    private String id;
    /** 风格名称，用户可编辑，可为空（展示时用 id 兜底） */
    private String name;
    private List<String> descriptionTags;
    private List<String> usageScenarioTags;
}
