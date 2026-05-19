package com.learning.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版式模板
 */
@Data
@TableName("layout_template")
public class LayoutTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    /** 版式类型：title/content/two_column/table 等 */
    @TableField("layout_type")
    private String layoutType;

    /** 缩略图 URL 或 base64（可选） */
    @TableField("thumbnail_url")
    private String thumbnailUrl;

    /** 版式配置 JSON（占位符、样式等） */
    @TableField("config_json")
    private String configJson;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
