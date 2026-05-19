package com.learning.template.controller;

import com.learning.common.Result;
import com.learning.template.dto.CreateLayoutRequest;
import com.learning.template.dto.ExampleLayoutItem;
import com.learning.template.service.ExampleLayoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 版式模板接口：版式列表来自 resources/examples/layouts.json，与 examples 下 HTML 对应
 */
@Slf4j
@RestController
@RequestMapping("/template")
@RequiredArgsConstructor
public class LayoutTemplateController {

    private final ExampleLayoutService exampleLayoutService;

    /**
     * 获取版式列表（从 examples/layouts.json 加载，用于版式管理页列表展示）
     */
    @GetMapping("/layouts")
    public Result<List<ExampleLayoutItem>> listLayouts() {
        List<ExampleLayoutItem> list = exampleLayoutService.list();
        return Result.success(list);
    }

    private static final String ERROR_HTML = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/></head><body><p style=\"padding:1rem;\">%s</p></body></html>";

    /**
     * 获取版式对应 HTML 预览内容（来自 examples 下文件）
     * 必须放在 /layouts/{code} 之前，否则 /layouts/A1/preview 会被匹配成 code=A1 且 404
     */
    @GetMapping(value = "/layouts/{code}/preview", produces = "text/html;charset=UTF-8")
    public String previewLayout(@PathVariable("code") String code) {
        try {
            if (code == null || code.isBlank()) {
                return String.format(ERROR_HTML, "版式编号为空");
            }
            ExampleLayoutItem item = exampleLayoutService.getByCode(code.trim());
            if (item == null) {
                return String.format(ERROR_HTML, "版式不存在: " + code);
            }
            return exampleLayoutService.readLayoutHtml(item);
        } catch (Exception e) {
            log.warn("Preview failed for code={}: {}", code, e.getMessage(), e);
            return String.format(ERROR_HTML, "预览加载失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * 根据编号获取单个版式
     */
    @GetMapping("/layouts/{code}")
    public Result<ExampleLayoutItem> getLayout(@PathVariable("code") String code) {
        ExampleLayoutItem item = exampleLayoutService.getByCode(code);
        if (item == null) {
            return Result.error(404, "版式不存在");
        }
        return Result.success(item);
    }

    /**
     * 更新版式名称和/或描述（持久化到覆盖文件，不影响 examples 下 HTML）
     */
    @PatchMapping("/layouts/{code}")
    public Result<Void> updateLayoutMeta(
            @PathVariable("code") String code,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        if (code == null || code.isBlank()) {
            return Result.error(400, "code 不能为空");
        }
        String codeTrim = code.trim();
        ExampleLayoutItem item = exampleLayoutService.getByCode(codeTrim);
        if (item == null) {
            exampleLayoutService.reloadIfEmptyOrMissing(codeTrim);
            item = exampleLayoutService.getByCode(codeTrim);
        }
        if (item == null) {
            List<ExampleLayoutItem> list = exampleLayoutService.list();
            log.warn("PATCH layout not found: code=[{}], cachedSize={}, codes={}",
                    codeTrim, list.size(),
                    list.stream().map(ExampleLayoutItem::getCode).limit(5).toList());
            return Result.error(404, "版式不存在");
        }
        String name = body != null ? body.get("name") : null;
        String description = body != null ? body.get("description") : null;
        if ((name == null || name.isBlank()) && (description == null || description.isBlank())) {
            return Result.success(null);
        }
        try {
            exampleLayoutService.updateLayoutMeta(codeTrim, name, description);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("Update layout meta failed: {}", e.getMessage());
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * 新增版式：保存 HTML 到 data 目录，并更新 data/layouts.json
     */
    @PostMapping("/layouts")
    public Result<ExampleLayoutItem> createLayout(@RequestBody CreateLayoutRequest req) {
        try {
            if (req == null) return Result.error(400, "请求体为空");
            ExampleLayoutItem item = exampleLayoutService.createLayout(
                    req.getName(),
                    req.getDescription(),
                    req.getTags(),
                    req.getHtml()
            );
            return Result.success(item);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("Create layout failed: {}", e.getMessage(), e);
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * 删除版式：从 data/layouts.json 移除，并尝试删除 data/results 下的 HTML 文件
     */
    @DeleteMapping("/layouts/{code}")
    public Result<Void> deleteLayout(@PathVariable("code") String code) {
        try {
            exampleLayoutService.deleteLayout(code);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("Delete layout failed: {}", e.getMessage(), e);
            return Result.error(500, e.getMessage());
        }
    }
}
