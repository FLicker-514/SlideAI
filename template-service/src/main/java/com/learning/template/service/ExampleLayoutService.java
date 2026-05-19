package com.learning.template.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.template.dto.ExampleLayoutItem;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 版式列表：优先从可写的 layouts.json（template.layouts-path）加载；
 * 若该文件不存在则从 classpath examples/layouts.json 复制一份再加载。
 * 前端修改名称/描述时直接写回该 layouts.json。
 */
@Slf4j
@Service
public class ExampleLayoutService {

    private static final String LAYOUTS_JSON_CLASSPATH = "examples/layouts.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${template.layouts-path:data/layouts.json}")
    private String layoutsPath;

    /**
     * 可写的 HTML 保存目录（用于新增版式）
     * 例如：data/results
     */
    @Value("${template.html-dir:data/results}")
    private String htmlDir;

    private List<ExampleLayoutItem> cached = Collections.emptyList();
    private final Object cacheLock = new Object();

    @PostConstruct
    public void load() {
        List<ExampleLayoutItem> fromClasspath = loadFromClasspath();
        Path path = resolveLayoutsPath();
        if (path != null) {
            log.info("Layouts path (template.layouts-path): {}", path.toAbsolutePath());
        }
        if (path != null && Files.isRegularFile(path)) {
            loadFromPath(path);
            synchronized (cacheLock) {
                if (!cached.isEmpty() && cached.stream().anyMatch(i -> i.getCode() != null && !i.getCode().isBlank())) {
                    log.info("Loaded {} layouts from {}", cached.size(), path);
                    return;
                }
            }
            log.warn("Writable layouts file empty or invalid, using classpath data");
        }
        if (!fromClasspath.isEmpty()) {
            synchronized (cacheLock) {
                cached = fromClasspath;
            }
            if (path != null) {
                try {
                    Files.createDirectories(path.getParent());
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), fromClasspath);
                    log.info("Loaded {} layouts from classpath and wrote to {}", fromClasspath.size(), path);
                } catch (Exception e) {
                    log.warn("Could not write to {}: {}, using classpath in memory only", path, e.getMessage());
                }
            } else {
                log.info("Loaded {} layouts from classpath only", fromClasspath.size());
            }
        }
    }

    /** 当缓存为空或查不到时尝试重新加载（先读可写文件，再回退 classpath），避免因路径/文件异常导致长期 404 */
    public void reloadIfEmptyOrMissing(String codeForLog) {
        synchronized (cacheLock) {
            if (!cached.isEmpty()) {
                boolean hasCode = cached.stream().anyMatch(i -> i.getCode() != null && !i.getCode().isBlank());
                if (hasCode) return;
            }
        }
        Path path = resolveLayoutsPath();
        if (path != null && Files.isRegularFile(path)) {
            loadFromPath(path);
            synchronized (cacheLock) {
                if (!cached.isEmpty() && cached.stream().anyMatch(i -> i.getCode() != null && !i.getCode().isBlank())) {
                    log.info("Reloaded {} layouts from {} (triggered by missing code={})", cached.size(), path, codeForLog);
                    return;
                }
            }
        }
        List<ExampleLayoutItem> fromClasspath = loadFromClasspath();
        if (fromClasspath.isEmpty()) return;
        synchronized (cacheLock) {
            cached = fromClasspath;
        }
        if (path != null) {
            try {
                Files.createDirectories(path.getParent());
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), fromClasspath);
            } catch (Exception e) {
                log.warn("Reload: could not write to {}: {}", path, e.getMessage());
            }
        }
        log.info("Reloaded {} layouts from classpath (triggered by missing code={})", fromClasspath.size(), codeForLog);
    }

    private Path resolveLayoutsPath() {
        if (layoutsPath == null || layoutsPath.isBlank()) return null;
        Path p = Paths.get(layoutsPath);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir", ".")).resolve(p);
        }
        return p.normalize();
    }

    private Path resolveHtmlDir() {
        if (htmlDir == null || htmlDir.isBlank()) return null;
        Path p = Paths.get(htmlDir);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir", ".")).resolve(p);
        }
        return p.normalize();
    }

    private void loadFromPath(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.startsWith("\uFEFF")) json = json.substring(1);
            List<ExampleLayoutItem> list = objectMapper.readValue(json, new TypeReference<>() {});
            synchronized (cacheLock) {
                cached = list != null ? list : List.of();
            }
        } catch (Exception e) {
            log.error("Failed to load layouts from {}: {}", path, e.getMessage(), e);
            synchronized (cacheLock) {
                cached = List.of();
            }
        }
    }

    private List<ExampleLayoutItem> loadFromClasspath() {
        InputStream is = null;
        try {
            ClassPathResource resource = new ClassPathResource(LAYOUTS_JSON_CLASSPATH);
            if (resource.exists()) {
                is = resource.getInputStream();
            }
            if (is == null) {
                is = getClass().getClassLoader().getResourceAsStream(LAYOUTS_JSON_CLASSPATH);
            }
            if (is == null) {
                log.warn("Resource not found: {}", LAYOUTS_JSON_CLASSPATH);
                return List.of();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (json.startsWith("\uFEFF")) json = json.substring(1);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to load " + LAYOUTS_JSON_CLASSPATH + ": " + e.getMessage(), e);
            return List.of();
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 更新某版式的名称和/或描述，直接写回 layouts.json
     */
    public void updateLayoutMeta(String code, String name, String description) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        Path path = resolveLayoutsPath();
        if (path == null) {
            throw new IllegalStateException("未配置 template.layouts-path");
        }
        String key = code.trim();
        List<ExampleLayoutItem> list;
        synchronized (cacheLock) {
            list = cached.stream().map(item -> {
                String c = item.getCode();
                if (c == null || !key.equalsIgnoreCase(c.trim())) return item;
                return new ExampleLayoutItem(
                        item.getCode(),
                        name != null && !name.isBlank() ? name.trim() : item.getName(),
                        description != null ? description.trim() : item.getDescription(),
                        item.getFile(),
                        item.getTags()
                );
            }).collect(Collectors.toList());
        }
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), list);
            synchronized (cacheLock) {
                cached = list;
            }
        } catch (Exception e) {
            log.error("Failed to save layouts.json: " + e.getMessage(), e);
            throw new RuntimeException("保存版式名称/描述失败: " + e.getMessage());
        }
    }

    public List<ExampleLayoutItem> list() {
        synchronized (cacheLock) {
            return List.copyOf(cached);
        }
    }

    public ExampleLayoutItem getByCode(String code) {
        if (code == null || code.isBlank()) return null;
        String key = code.trim();
        synchronized (cacheLock) {
            return cached.stream()
                    .filter(item -> item.getCode() != null && key.equalsIgnoreCase(item.getCode().trim()))
                    .findFirst()
                    .orElse(null);
        }
    }

    public ExampleLayoutItem createLayout(String name, String description, List<String> tags, String html) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("html 不能为空");
        }
        String safeName = name.trim();
        String safeDesc = description != null ? description.trim() : "";
        List<String> safeTags = tags != null
                ? tags.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList()
                : List.of();

        Path layoutsJsonPath = resolveLayoutsPath();
        if (layoutsJsonPath == null) {
            throw new IllegalStateException("未配置 template.layouts-path");
        }
        Path htmlDirPath = resolveHtmlDir();
        if (htmlDirPath == null) {
            throw new IllegalStateException("未配置 template.html-dir");
        }

        String code = generateUniqueCode();
        String fileName = code + ".html";

        try {
            Files.createDirectories(layoutsJsonPath.getParent());
            Files.createDirectories(htmlDirPath);
            Files.writeString(htmlDirPath.resolve(fileName), html, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("写入 HTML 失败: " + e.getMessage(), e);
        }

        ExampleLayoutItem newItem = new ExampleLayoutItem(code, safeName, safeDesc, fileName, safeTags);
        List<ExampleLayoutItem> next;
        synchronized (cacheLock) {
            next = new java.util.ArrayList<>(cached);
            next.add(0, newItem);
        }
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(layoutsJsonPath.toFile(), next);
            synchronized (cacheLock) {
                cached = next;
            }
        } catch (Exception e) {
            throw new RuntimeException("写入 layouts.json 失败: " + e.getMessage(), e);
        }
        return newItem;
    }

    public void deleteLayout(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        String key = code.trim();
        Path layoutsJsonPath = resolveLayoutsPath();
        if (layoutsJsonPath == null) {
            throw new IllegalStateException("未配置 template.layouts-path");
        }

        ExampleLayoutItem removed;
        List<ExampleLayoutItem> next;
        synchronized (cacheLock) {
            removed = cached.stream()
                    .filter(i -> i.getCode() != null && key.equalsIgnoreCase(i.getCode().trim()))
                    .findFirst()
                    .orElse(null);
            if (removed == null) {
                throw new IllegalArgumentException("版式不存在");
            }
            next = cached.stream()
                    .filter(i -> i.getCode() == null || !key.equalsIgnoreCase(i.getCode().trim()))
                    .collect(Collectors.toList());
        }

        // 1) 先写回 layouts.json
        try {
            Files.createDirectories(layoutsJsonPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(layoutsJsonPath.toFile(), next);
            synchronized (cacheLock) {
                cached = next;
            }
        } catch (Exception e) {
            throw new RuntimeException("写入 layouts.json 失败: " + e.getMessage(), e);
        }

        // 2) 尝试删除 data/results 下的 HTML（若不存在则忽略）
        try {
            String file = Optional.ofNullable(removed.getFile()).orElse("").replace("..", "").trim();
            if (!file.isBlank()) {
                Path htmlDirPath = resolveHtmlDir();
                if (htmlDirPath != null) {
                    Path p = htmlDirPath.resolve(file).normalize();
                    if (p.startsWith(htmlDirPath) && Files.exists(p)) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (Exception e) {
            // 删除失败不影响元数据移除，但记录日志方便排查
            log.warn("Delete layout html file failed for code={}: {}", key, e.getMessage());
        }
    }

    private String generateUniqueCode() {
        // 8 位 base36：与当前 data/layouts.json 的 code 风格一致
        for (int i = 0; i < 50; i++) {
            String candidate = randomBase36(8);
            if (getByCode(candidate) == null) return candidate;
        }
        // 极端情况下兜底
        return randomBase36(10);
    }

    private String randomBase36(int len) {
        final char[] alphabet = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet[secureRandom.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    /**
     * 读取版式 HTML：优先读取 data 目录（template.html-dir），回退到 classpath examples/
     */
    public String readLayoutHtml(ExampleLayoutItem item) throws Exception {
        if (item == null) throw new IllegalArgumentException("item 不能为空");
        String file = item.getFile();
        if (file == null || file.isBlank()) throw new IllegalArgumentException("版式未配置 file");
        String safeFile = file.replace("..", "").trim();

        Path htmlDirPath = resolveHtmlDir();
        if (htmlDirPath != null) {
            Path p = htmlDirPath.resolve(safeFile).normalize();
            if (p.startsWith(htmlDirPath) && Files.isRegularFile(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        }

        ClassPathResource resource = new ClassPathResource("examples/" + safeFile);
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

