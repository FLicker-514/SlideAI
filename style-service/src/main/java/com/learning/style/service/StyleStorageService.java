package com.learning.style.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.style.dto.StyleHistoryItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 风格存储：Userdata/{userId}/ 下 history.json、每风格一张背景图 {styleId}-background.png（或兼容旧版 -background-1/2/3）、{styleId}-font.json。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StyleStorageService {

    private static final String USERDATA = "Userdata";
    private static final String HISTORY_FILE = "history.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${style.project-root:}")
    private String projectRootConfig;

    @Value("${style.script-path:}")
    private String scriptPathConfig;

    @Value("${style.python-path:python3}")
    private String pythonPath;

    @Value("${llm.service.base-url:}")
    private String llmServiceBaseUrl;

    private final RestTemplate restTemplate;

    private Path resolveProjectRoot() {
        if (projectRootConfig != null && !projectRootConfig.isBlank()) {
            return Paths.get(projectRootConfig).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Path resolveScriptPath(Path projectRoot) {
        if (scriptPathConfig != null && !scriptPathConfig.isBlank()) {
            Path p = Paths.get(scriptPathConfig);
            return p.isAbsolute() ? p : projectRoot.resolve(scriptPathConfig).normalize();
        }
        Path inStyleService = projectRoot.resolve("style-service").resolve("extract_style_from_ppt.py").normalize();
        if (Files.exists(inStyleService)) return inStyleService;
        return projectRoot.resolve("extract_style_from_ppt.py").normalize();
    }

    /** 用户风格目录 */
    public Path userStyleDir(String userId) {
        return resolveProjectRoot().resolve(USERDATA).resolve(userId);
    }

    /** 列出该用户所有风格（读 history.json） */
    public List<StyleHistoryItem> list(String userId) throws IOException {
        if (userId == null || userId.isBlank()) return List.of();
        Path historyPath = userStyleDir(userId).resolve(HISTORY_FILE);
        if (!Files.exists(historyPath)) return List.of();
        String content = Files.readString(historyPath, StandardCharsets.UTF_8);
        List<StyleHistoryItem> list = objectMapper.readValue(content, new TypeReference<>() {});
        return list != null ? list : List.of();
    }

    /** 读取某风格：每个风格一张背景图，读后包装为 HTML；返回的 background1/2/3 均为同一张图（供前端三 tab 复用）。font.json 与字体演示 HTML */
    public StyleDetail readDetail(String userId, String styleId) throws IOException {
        Path dir = userStyleDir(userId);
        String singleHtml = readBackgroundImage(dir, styleId);
        String fontJson = readFileIfExists(dir.resolve(styleId + "-font.json"));
        String fontDemoHtml = readFileIfExists(dir.resolve(styleId + "-font-demo.html"));
        return new StyleDetail(singleHtml, singleHtml, singleHtml, fontJson, fontDemoHtml);
    }

    /** 读单张背景：优先 {styleId}-background.png（16:9 截图），再 .html（仅背景），兼容旧版 -background-1.* */
    private String readBackgroundImage(Path dir, String styleId) throws IOException {
        Path singlePng = dir.resolve(styleId + "-background.png");
        if (Files.exists(singlePng)) {
            byte[] bytes = Files.readAllBytes(singlePng);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0;\">"
                    + "<img src=\"data:image/png;base64," + base64 + "\" style=\"width:100%;height:100%;object-fit:contain;display:block;\"/></body></html>";
        }
        Path singleHtml = dir.resolve(styleId + "-background.html");
        if (Files.exists(singleHtml)) {
            return Files.readString(singleHtml, StandardCharsets.UTF_8);
        }
        Path legacyPng = dir.resolve(styleId + "-background-1.png");
        if (Files.exists(legacyPng)) {
            byte[] bytes = Files.readAllBytes(legacyPng);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0;\">"
                    + "<img src=\"data:image/png;base64," + base64 + "\" style=\"width:100%;height:100%;object-fit:contain;display:block;\"/></body></html>";
        }
        Path legacyHtml = dir.resolve(styleId + "-background-1.html");
        if (Files.exists(legacyHtml)) {
            return Files.readString(legacyHtml, StandardCharsets.UTF_8);
        }
        return "";
    }

    private String readFileIfExists(Path path) throws IOException {
        if (!Files.exists(path)) return "";
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 从上传的 PPT 生成新风格：调用 Python 脚本生成 3 个 background HTML + font.json，并写入 history.json。
     */
    public String createFromPpt(String userId, MultipartFile pptFile,
                                List<String> descriptionTags, List<String> usageScenarioTags) throws IOException {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空");
        if (pptFile == null || pptFile.isEmpty()) throw new IllegalArgumentException("请上传 PPT 文件");

        String styleId = UUID.randomUUID().toString().replace("-", "");
        Path projectRoot = resolveProjectRoot();
        Path userDir = userStyleDir(userId);
        Files.createDirectories(userDir);

        Path tempPpt = Files.createTempFile("style_", ".pptx");
        try {
            pptFile.transferTo(tempPpt.toFile());
            Path scriptPath = resolveScriptPath(projectRoot);
            if (!Files.exists(scriptPath)) {
                throw new IllegalStateException("未找到提取脚本: " + scriptPath + "，请配置 style.script-path 或将 extract_style_from_ppt.py 放在 style-service 目录");
            }
            ProcessBuilder pb = new ProcessBuilder(
                    pythonPath,
                    scriptPath.toString(),
                    "--ppt", tempPpt.toString(),
                    "--style-id", styleId,
                    "--output-dir", userDir.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("extract_style_from_ppt.py 退出码 {}: {}", exit, out);
                throw new IOException("PPT 提取失败: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("提取被中断", e);
        } finally {
            Files.deleteIfExists(tempPpt);
        }

        // 生成 PNG 后删除可能遗留的 HTML（兼容截图失败回退或旧版残留）
        cleanupBackgroundHtmlIfPngExists(userDir, styleId);

        appendStyleToHistory(userDir, styleId, null, descriptionTags, usageScenarioTags);
        return styleId;
    }

    /**
     * 从描述生成新风格：调用 llm-service 文生图 + 风格标签，生成一张背景图（16:9 无文字留白）并写入 history。
     */
    @SuppressWarnings("unchecked")
    public CreateFromDescriptionResult createFromDescription(String userId, String name, String description) throws IOException {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description 不能为空");
        if (llmServiceBaseUrl == null || llmServiceBaseUrl.isBlank()) {
            throw new IllegalStateException("未配置 llm.service.base-url，无法通过描述生成风格");
        }

        String baseUrl = llmServiceBaseUrl.replaceAll("/$", "");
        RestTemplate rt = restTemplate;

        Map<String, Object> imageReq = Map.of("prompt", description.trim());
        ResponseEntity<com.learning.common.Result> imageResp = rt.postForEntity(
                baseUrl + "/llm/image/generate",
                new HttpEntity<>(imageReq, jsonHeaders()),
                com.learning.common.Result.class
        );
        com.learning.common.Result<?> imageResult = imageResp.getBody();
        if (imageResult == null || imageResult.getCode() != 200 || imageResult.getData() == null) {
            String msg = imageResult != null ? imageResult.getMessage() : "无响应";
            throw new IOException("文生图失败: " + msg);
        }
        Map<String, Object> imageData = (Map<String, Object>) imageResult.getData();
        String imageBase64 = (String) imageData.get("imageBase64");
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IOException("文生图返回无 imageBase64");
        }

        Map<String, Object> tagsReq = Map.of("description", description.trim());
        ResponseEntity<com.learning.common.Result> tagsResp = rt.postForEntity(
                baseUrl + "/llm/style-tags",
                new HttpEntity<>(tagsReq, jsonHeaders()),
                com.learning.common.Result.class
        );
        com.learning.common.Result<?> tagsResult = tagsResp.getBody();
        List<String> descriptionTags = List.of();
        List<String> usageScenarioTags = List.of();
        if (tagsResult != null && tagsResult.getCode() == 200 && tagsResult.getData() != null) {
            Map<String, Object> tagsData = (Map<String, Object>) tagsResult.getData();
            if (tagsData.get("descriptionTags") instanceof List) {
                descriptionTags = (List<String>) tagsData.get("descriptionTags");
            }
            if (tagsData.get("usageScenarioTags") instanceof List) {
                usageScenarioTags = (List<String>) tagsData.get("usageScenarioTags");
            }
        }

        String styleId = UUID.randomUUID().toString().replace("-", "");
        Path userDir = userStyleDir(userId);
        Files.createDirectories(userDir);

        byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
        Files.write(userDir.resolve(styleId + "-background.png"), imageBytes);

        // 生成推荐字体与字体演示 HTML（用于预览与可编辑保存）
        Map<String, String> fontSuggest = analyzeFontsFromImage(baseUrl, description.trim(), imageBase64);
        String heading3Font = fontSuggest.getOrDefault("heading3Font", "");
        String bodyFont = fontSuggest.getOrDefault("bodyFont", "");
        String fontHtml = fontSuggest.getOrDefault("fontHtml", "");
        if (!fontHtml.isBlank()) {
            Files.writeString(userDir.resolve(styleId + "-font-demo.html"), fontHtml, StandardCharsets.UTF_8);
        }
        String fontJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("heading3", heading3Font, "body", bodyFont));
        Files.writeString(userDir.resolve(styleId + "-font.json"), fontJson, StandardCharsets.UTF_8);

        String savedName = (name != null && !name.isBlank()) ? name.trim() : null;
        appendStyleToHistory(userDir, styleId, savedName, descriptionTags, usageScenarioTags);
        return new CreateFromDescriptionResult(styleId, savedName, descriptionTags, usageScenarioTags, heading3Font, bodyFont);
    }

    /** 通过描述生成风格的返回结果，供控制器返回给前端（名称、风格标签、适用场景标签） */
    public static class CreateFromDescriptionResult {
        public final String id;
        public final String name;
        public final List<String> descriptionTags;
        public final List<String> usageScenarioTags;
        public final String heading3Font;
        public final String bodyFont;

        public CreateFromDescriptionResult(String id, String name, List<String> descriptionTags, List<String> usageScenarioTags,
                                           String heading3Font, String bodyFont) {
            this.id = id;
            this.name = name != null ? name : "";
            this.descriptionTags = descriptionTags != null ? descriptionTags : List.of();
            this.usageScenarioTags = usageScenarioTags != null ? usageScenarioTags : List.of();
            this.heading3Font = heading3Font != null ? heading3Font : "";
            this.bodyFont = bodyFont != null ? bodyFont : "";
        }
    }

    private void cleanupBackgroundHtmlIfPngExists(Path userDir, String styleId) {
        try {
            if (!Files.exists(userDir.resolve(styleId + "-background.png"))) return;
            Files.deleteIfExists(userDir.resolve(styleId + "-background.html"));
            for (int p = 1; p <= 3; p++) {
                Files.deleteIfExists(userDir.resolve(styleId + "-background-" + p + ".html"));
            }
        } catch (Exception e) {
            log.warn("清理 background HTML 失败: {}", e.getMessage());
        }
    }

    /**
     * 调用 llm-service 的 style-analyze，基于背景图给出推荐字体与字体演示 HTML。
     * 返回 keys: heading3Font, bodyFont, fontHtml
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> analyzeFontsFromImage(String baseUrl, String userHint, String imageBase64) {
        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("messages", List.of(Map.of("role", "user", "content", "【用户参考】" + userHint)));
            reqBody.put("imageBase64", imageBase64);
            reqBody.put("imageMediaType", "image/png");
            String url = baseUrl + "/llm/chat?provider=qwen&promptKey=style-analyze";
            ResponseEntity<com.learning.common.Result> resp = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(reqBody, jsonHeaders()),
                    com.learning.common.Result.class
            );
            com.learning.common.Result<?> r = resp.getBody();
            if (r == null || r.getCode() != 200 || r.getData() == null) return Map.of();
            Object data = r.getData();
            String content = null;
            if (data instanceof Map) {
                Object c = ((Map<String, Object>) data).get("content");
                if (c instanceof String) content = (String) c;
                if (content == null) {
                    Object inner = ((Map<String, Object>) data).get("data");
                    if (inner instanceof Map) {
                        Object c2 = ((Map<String, Object>) inner).get("content");
                        if (c2 instanceof String) content = (String) c2;
                    }
                }
            }
            if (content == null || content.isBlank()) return Map.of();
            String json = content.trim().replaceAll("^```(?:json)?\\s*|\\s*```$", "").trim();
            Map<String, Object> parsed;
            try {
                parsed = objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception e) {
                int s = json.indexOf('{');
                int t = json.lastIndexOf('}');
                if (s != -1 && t > s) {
                    parsed = objectMapper.readValue(json.substring(s, t + 1), new TypeReference<>() {});
                } else {
                    return Map.of();
                }
            }
            String heading3Font = parsed.get("heading3Font") instanceof String ? (String) parsed.get("heading3Font") : "";
            String bodyFont = parsed.get("bodyFont") instanceof String ? (String) parsed.get("bodyFont") : "";
            String fontHtml = parsed.get("fontHtml") instanceof String ? (String) parsed.get("fontHtml") : "";
            return Map.of("heading3Font", heading3Font, "bodyFont", bodyFont, "fontHtml", fontHtml);
        } catch (Exception e) {
            log.warn("style-analyze 生成字体建议失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void appendStyleToHistory(Path userDir, String styleId, String name,
                                      List<String> descriptionTags, List<String> usageScenarioTags) throws IOException {
        List<StyleHistoryItem> history = new ArrayList<>();
        Path historyPath = userDir.resolve(HISTORY_FILE);
        if (Files.exists(historyPath)) {
            try {
                List<StyleHistoryItem> existing = objectMapper.readValue(
                        Files.readString(historyPath, StandardCharsets.UTF_8), new TypeReference<>() {});
                if (existing != null) history.addAll(existing);
            } catch (Exception e) {
                log.warn("读取 history.json 失败: {}", e.getMessage());
            }
        }
        StyleHistoryItem item = new StyleHistoryItem();
        item.setId(styleId);
        item.setName(name);
        item.setDescriptionTags(descriptionTags != null ? descriptionTags : List.of());
        item.setUsageScenarioTags(usageScenarioTags != null ? usageScenarioTags : List.of());
        history.add(item);
        saveHistory(historyPath, history);
    }

    /** 更新某风格的名称、标签、字体演示 HTML 与 font.json（Qwen 推荐的三级标题+正文字体） */
    public void updateMeta(String userId, String styleId, String name,
                           List<String> descriptionTags, List<String> usageScenarioTags,
                           String fontDemoHtml, String heading3Font, String bodyFont) throws IOException {
        if (userId == null || userId.isBlank() || styleId == null || styleId.isBlank()) {
            throw new IllegalArgumentException("userId 与 styleId 不能为空");
        }
        Path userDir = userStyleDir(userId);
        Path historyPath = userDir.resolve(HISTORY_FILE);
        if (!Files.exists(historyPath)) {
            throw new IOException("未找到风格列表");
        }
        List<StyleHistoryItem> history = objectMapper.readValue(
                Files.readString(historyPath, StandardCharsets.UTF_8), new TypeReference<>() {});
        StyleHistoryItem target = null;
        for (StyleHistoryItem it : history) {
            if (styleId.equals(it.getId())) {
                target = it;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("风格不存在: " + styleId);
        }
        if (name != null) target.setName(name.isBlank() ? null : name.trim());
        if (descriptionTags != null) target.setDescriptionTags(descriptionTags);
        if (usageScenarioTags != null) target.setUsageScenarioTags(usageScenarioTags);
        saveHistory(historyPath, history);
        if (fontDemoHtml != null) {
            Path demoPath = userDir.resolve(styleId + "-font-demo.html");
            if (fontDemoHtml.isBlank()) {
                Files.deleteIfExists(demoPath);
            } else {
                Files.writeString(demoPath, fontDemoHtml, StandardCharsets.UTF_8);
            }
        }
        if (heading3Font != null || bodyFont != null) {
            String h3 = heading3Font != null ? heading3Font.trim() : "";
            String body = bodyFont != null ? bodyFont.trim() : "";
            String fontJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("heading3", h3, "body", body));
            Files.writeString(userDir.resolve(styleId + "-font.json"), fontJson, StandardCharsets.UTF_8);
        }
    }

    private void saveHistory(Path historyPath, List<StyleHistoryItem> history) throws IOException {
        Files.writeString(historyPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(history), StandardCharsets.UTF_8);
    }

    /**
     * 删除某风格：删除对应的 3 个 background HTML、font.json，并从 history.json 中移除该项。
     */
    public void deleteStyle(String userId, String styleId) throws IOException {
        if (userId == null || userId.isBlank() || styleId == null || styleId.isBlank()) {
            throw new IllegalArgumentException("userId 与 styleId 不能为空");
        }
        Path userDir = userStyleDir(userId);
        Path historyPath = userDir.resolve(HISTORY_FILE);
        if (!Files.exists(historyPath)) {
            throw new IllegalArgumentException("风格不存在: " + styleId);
        }
        List<StyleHistoryItem> history = objectMapper.readValue(
                Files.readString(historyPath, StandardCharsets.UTF_8), new TypeReference<>() {});
        boolean found = false;
        for (StyleHistoryItem it : history) {
            if (styleId.equals(it.getId())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("风格不存在: " + styleId);
        }
        Files.deleteIfExists(userDir.resolve(styleId + "-background.html"));
        Files.deleteIfExists(userDir.resolve(styleId + "-background.png"));
        for (int p = 1; p <= 3; p++) {
            Files.deleteIfExists(userDir.resolve(styleId + "-background-" + p + ".png"));
            Files.deleteIfExists(userDir.resolve(styleId + "-background-" + p + ".html"));
        }
        Files.deleteIfExists(userDir.resolve(styleId + "-font.json"));
        Files.deleteIfExists(userDir.resolve(styleId + "-font-demo.html"));
        List<StyleHistoryItem> newHistory = history.stream()
                .filter(it -> !styleId.equals(it.getId()))
                .collect(Collectors.toList());
        saveHistory(historyPath, newHistory);
    }

    /** 某风格的 3 页背景 HTML、字体 JSON 与字体演示 HTML（LLM 生成，一级/二级/三级标题+正文） */
    public static class StyleDetail {
        public final String background1;
        public final String background2;
        public final String background3;
        public final String fontJson;
        public final String fontDemoHtml;

        public StyleDetail(String background1, String background2, String background3, String fontJson, String fontDemoHtml) {
            this.background1 = background1 != null ? background1 : "";
            this.background2 = background2 != null ? background2 : "";
            this.background3 = background3 != null ? background3 : "";
            this.fontJson = fontJson != null ? fontJson : "{}";
            this.fontDemoHtml = fontDemoHtml != null ? fontDemoHtml : "";
        }
    }
}
