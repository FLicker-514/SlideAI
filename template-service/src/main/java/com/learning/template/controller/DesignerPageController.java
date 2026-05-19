package com.learning.template.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 设计器静态页路由：
 * Spring Boot 默认不会对目录自动返回 index.html，因此补一个 forward。
 *
 * 实际访问路径会带上 context-path(/api)：
 * - /api/designer
 * - /api/designer/
 */
@Controller
public class DesignerPageController {

    @GetMapping({"/designer", "/designer/"})
    public String designerIndex() {
        return "forward:/designer/index.html";
    }
}

