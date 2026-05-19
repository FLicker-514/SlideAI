package com.learning.template.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 来自 resources/examples/layouts.json 的版式条目
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExampleLayoutItem {

    /** 版式编号，如 A1 */
    private String code;

    /** 版式名称，如 封面/标题 */
    private String name;

    /** 版式描述（与版式.txt 对应） */
    private String description;

    /** 对应 examples 下的 HTML 文件名 */
    private String file;

    /**
     * 标签（用于筛选/分类）
     * 候选：封面页 / 目录页 / 分隔页 / 结尾页 / 内容页（有配图） / 内容页（无配图）
     */
    private List<String> tags;
}
