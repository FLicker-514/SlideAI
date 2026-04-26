package com.learning.component.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.component.dto.ComponentDetailResponse;
import com.learning.component.dto.ComponentItem;
import com.learning.component.dto.ComponentSaveRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ComponentStorageService {

    private final ObjectMapper objectMapper;

    @Value("${component.storage.base-dir:Userdata}")
    private String baseDir;

    public ComponentStorageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private Path userDir(String userId) {
        return Path.of(baseDir, userId);
    }

    private Path userIndexPath(String userId) {
        return userDir(userId).resolve("components.json");
    }

    private Path userHtmlDir(String userId) {
        return userDir(userId).resolve("html");
    }

    private void ensureUserDirs(String userId) throws IOException {
        Files.createDirectories(userHtmlDir(userId));
    }

    private List<ComponentItem> readIndex(String userId) {
        Path p = userIndexPath(userId);
        if (!Files.exists(p)) return new ArrayList<>();
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(json)) return new ArrayList<>();
            List<ComponentItem> list = objectMapper.readValue(json, new TypeReference<List<ComponentItem>>() {});
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeIndex(String userId, List<ComponentItem> list) throws IOException {
        ensureUserDirs(userId);
        Path p = userIndexPath(userId);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
        // 原子写入：先写临时文件再替换
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public List<ComponentItem> list(String userId) {
        List<ComponentItem> list = readIndex(userId);
        list.sort(Comparator.comparing(ComponentItem::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed());
        return list;
    }

    public Optional<ComponentDetailResponse> getDetail(String userId, String id) {
        List<ComponentItem> list = readIndex(userId);
        ComponentItem hit = list.stream().filter(x -> id.equals(x.getId())).findFirst().orElse(null);
        if (hit == null) return Optional.empty();
        Path htmlPath = userDir(userId).resolve(hit.getHtmlFile());
        if (!Files.exists(htmlPath)) {
            return Optional.of(ComponentDetailResponse.builder().meta(hit).html("").build());
        }
        try {
            String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
            return Optional.of(ComponentDetailResponse.builder().meta(hit).html(html).build());
        } catch (Exception e) {
            return Optional.of(ComponentDetailResponse.builder().meta(hit).html("").build());
        }
    }

    public ComponentItem save(ComponentSaveRequest req) throws IOException {
        String userId = req.getUserId();
        if (!StringUtils.hasText(userId)) throw new IllegalArgumentException("userId 不能为空");
        if (!StringUtils.hasText(req.getName())) throw new IllegalArgumentException("name 不能为空");
        if (!StringUtils.hasText(req.getDescription())) throw new IllegalArgumentException("description 不能为空");
        if (!StringUtils.hasText(req.getHtml())) throw new IllegalArgumentException("html 不能为空");

        ensureUserDirs(userId);
        List<ComponentItem> list = readIndex(userId);

        String id = UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        String htmlRel = Path.of("html", id + ".html").toString().replace("\\", "/");
        Path htmlPath = userDir(userId).resolve(htmlRel);
        Files.writeString(htmlPath, req.getHtml(), StandardCharsets.UTF_8);

        ComponentItem item = ComponentItem.builder()
                .id(id)
                .userId(userId)
                .name(req.getName().trim())
                .description(req.getDescription().trim())
                .htmlFile(htmlRel)
                .createdAt(now)
                .updatedAt(now)
                .build();
        list.add(0, item);
        writeIndex(userId, list);
        return item;
    }

    public boolean delete(String userId, String id) throws IOException {
        List<ComponentItem> list = readIndex(userId);
        ComponentItem hit = list.stream().filter(x -> id.equals(x.getId())).findFirst().orElse(null);
        if (hit == null) return false;
        list.removeIf(x -> id.equals(x.getId()));
        writeIndex(userId, list);
        // 删除 html 文件
        try {
            Path htmlPath = userDir(userId).resolve(hit.getHtmlFile());
            Files.deleteIfExists(htmlPath);
        } catch (Exception ignored) {}
        return true;
    }
}

