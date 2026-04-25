package com.learning.style.controller;

import com.learning.common.Result;
import com.learning.style.dto.StyleHistoryItem;
import com.learning.style.service.StyleStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 风格管理：预览已有、生成新的（上传 PPT 提取第 1/2/末页背景 + 字体）。
 */
@Slf4j
@RestController
@RequestMapping("/style")
@RequiredArgsConstructor
public class StyleController {

    private final StyleStorageService styleStorageService;

    /** 列出用户风格列表（history.json） */
    @GetMapping("/list")
    public Result<List<StyleHistoryItem>> list(@RequestParam("userId") String userId) {
        if (userId == null || userId.isBlank()) {
            return Result.success(List.of());
        }
        try {
            return Result.success(styleStorageService.list(userId));
        } catch (IOException e) {
            log.error("读取风格列表失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 获取某风格详情：3 页背景 HTML + font.json */
    @GetMapping("/detail")
    public Result<Map<String, Object>> detail(
            @RequestParam("userId") String userId,
            @RequestParam("styleId") String styleId) {
        if (userId == null || userId.isBlank() || styleId == null || styleId.isBlank()) {
            return Result.error(400, "userId 与 styleId 不能为空");
        }
        try {
            StyleStorageService.StyleDetail d = styleStorageService.readDetail(userId, styleId);
            return Result.success(Map.of(
                    "background1", d.background1,
                    "background2", d.background2,
                    "background3", d.background3,
                    "fontJson", d.fontJson,
                    "fontDemoHtml", d.fontDemoHtml != null ? d.fontDemoHtml : ""
            ));
        } catch (IOException e) {
            log.error("读取风格详情失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /**
     * 通过描述生成新风格：调用 llm-service 文生图 + 风格标签，保存一张背景图与 history。
     * 返回 id、name、descriptionTags、usageScenarioTags，供前端与「从 PPT 上传」一致地展示并支持编辑后保存。
     * Body: userId（必填）, description（必填）, name（可选）。
     */
    @PostMapping("/create-from-description")
    public Result<Map<String, Object>> createFromDescription(@RequestBody Map<String, String> body) {
        String userId = body != null ? body.get("userId") : null;
        String description = body != null ? body.get("description") : null;
        String name = body != null ? body.get("name") : null;
        if (userId == null || userId.isBlank()) {
            return Result.error(400, "userId 不能为空");
        }
        if (description == null || description.isBlank()) {
            return Result.error(400, "description 不能为空");
        }
        try {
            StyleStorageService.CreateFromDescriptionResult result = styleStorageService.createFromDescription(userId, name, description);
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("id", result.id);
            data.put("name", result.name != null ? result.name : "");
            data.put("descriptionTags", result.descriptionTags != null ? result.descriptionTags : List.of());
            data.put("usageScenarioTags", result.usageScenarioTags != null ? result.usageScenarioTags : List.of());
            data.put("heading3Font", result.heading3Font != null ? result.heading3Font : "");
            data.put("bodyFont", result.bodyFont != null ? result.bodyFont : "");
            return Result.success(data);
        } catch (IllegalStateException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("描述生成风格失败", e);
            return Result.error(500, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 生成新风格：上传 PPT，提取第 1/2/末页背景与主要字体，保存到 Userdata/{userId}/{styleId}-background-1/2/3.html、{styleId}-font.json，并写入 history.json。
     * 表单字段：file（必填）, userId（必填）, descriptionTags（可选，逗号分隔）, usageScenarioTags（可选，逗号分隔）。
     */
    @PostMapping(value = "/create", consumes = "multipart/form-data")
    public Result<Map<String, String>> create(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId,
            @RequestParam(value = "descriptionTags", required = false) String descriptionTagsStr,
            @RequestParam(value = "usageScenarioTags", required = false) String usageScenarioTagsStr) {
        if (userId == null || userId.isBlank()) {
            return Result.error(400, "userId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传 PPT 文件");
        }
        List<String> descriptionTags = parseTags(descriptionTagsStr);
        List<String> usageScenarioTags = parseTags(usageScenarioTagsStr);
        try {
            String styleId = styleStorageService.createFromPpt(userId, file, descriptionTags, usageScenarioTags);
            return Result.success(Map.of("id", styleId));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("生成风格失败", e);
            return Result.error(500, "生成失败: " + e.getMessage());
        }
    }

    /** 更新风格名称与标签（body: name?, descriptionTags?, usageScenarioTags?） */
    @PatchMapping("/update")
    public Result<Void> update(
            @RequestParam("userId") String userId,
            @RequestParam("styleId") String styleId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || styleId == null || styleId.isBlank()) {
            return Result.error(400, "userId 与 styleId 不能为空");
        }
        String name = body != null && body.containsKey("name") ? String.valueOf(body.get("name")) : null;
        List<String> descriptionTags = toListOfStrings(body != null ? body.get("descriptionTags") : null);
        List<String> usageScenarioTags = toListOfStrings(body != null ? body.get("usageScenarioTags") : null);
        String fontDemoHtml = body != null && body.containsKey("fontDemoHtml") ? String.valueOf(body.get("fontDemoHtml")) : null;
        String heading3Font = body != null && body.containsKey("heading3Font") ? String.valueOf(body.get("heading3Font")) : null;
        String bodyFont = body != null && body.containsKey("bodyFont") ? String.valueOf(body.get("bodyFont")) : null;
        try {
            styleStorageService.updateMeta(userId, styleId, name, descriptionTags, usageScenarioTags, fontDemoHtml, heading3Font, bodyFont);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("更新风格失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 删除风格：删除对应 HTML、font.json，并从 history.json 中移除 */
    @DeleteMapping("/delete")
    public Result<Void> delete(
            @RequestParam("userId") String userId,
            @RequestParam("styleId") String styleId) {
        if (userId == null || userId.isBlank() || styleId == null || styleId.isBlank()) {
            return Result.error(400, "userId 与 styleId 不能为空");
        }
        try {
            styleStorageService.deleteStyle(userId, styleId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("删除风格失败", e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    private static List<String> parseTags(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> list = new java.util.ArrayList<>();
        for (String part : s.split("[,，]")) {
            String t = part.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    /** 将 body 中的 List 转为 List<String>，避免 Jackson 反序列化导致的类型问题 */
    @SuppressWarnings("unchecked")
    private static List<String> toListOfStrings(Object value) {
        if (value == null) return null;
        if (!(value instanceof List)) return null;
        List<?> list = (List<?>) value;
        return list.stream().map(o -> o == null ? "" : String.valueOf(o)).collect(Collectors.toList());
    }
}
