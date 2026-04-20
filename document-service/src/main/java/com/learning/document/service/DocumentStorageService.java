package com.learning.document.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文档存储：Userdata/{userId}/PDF、Userdata/{userId}/Images、Userdata/{userId}/ppt。
 * PDF 目录下含 parse-status.json：0=未解析，1=解析中，2=已解析。
 * ppt 目录下每份创作一个子目录 {pptId}/，内含 process.json、upload.json、outline.json 等。
 */
@Slf4j
@Service
public class DocumentStorageService {

    private static final String USERDATA = "Userdata";
    private static final String PDF_DIR = "PDF";
    /** 图片目录：Userdata/{userId}/Images/{imageId}/ 下存 image 文件 + info.json */
    private static final String IMAGES_DIR = "Images";
    /** PPT 创作目录：Userdata/{userId}/ppt/{pptId}/ 下存 process.json、upload.json 等 */
    private static final String PPT_DIR = "ppt";
    private static final String PPT_INDEX_JSON = "index.json";
    private static final String PROCESS_JSON = "process.json";
    private static final String UPLOAD_JSON = "upload.json";
    private static final String OUTLINE_JSON = "outline.json";
    private static final String PAGE_CONTENTS_JSON = "pageContents.json";
    private static final String LAYOUT_CODES_JSON = "layoutCodes.json";
    private static final String STYLE_JSON = "style.json";
    private static final String GENERATED_PAGES_JSON = "generatedPages.json";
    private static final String IMAGE_INFO_FILE = "info.json";
    private static final String CONTENT_MD = "content.md";
    private static final String IMAGES_FOLDER = "images";
    private static final String PARSE_STATUS_FILE = "parse-status.json";

    private static final ObjectMapper PPT_MAPPER = new ObjectMapper();

    /** 0=未解析，1=解析中，2=已解析 */
    public static final int PARSE_STATUS_NOT_PARSED = 0;
    public static final int PARSE_STATUS_PARSING = 1;
    public static final int PARSE_STATUS_PARSED = 2;

    @Value("${document.project-root:}")
    private String projectRootConfig;

    @Value("${document.python-path:python3}")
    private String pythonPath;

    @Value("${document.parser-script:}")
    private String parserScriptConfig;

    @Value("${document.caption-script:}")
    private String captionScriptConfig;

    private Path resolveProjectRoot() {
        if (projectRootConfig != null && !projectRootConfig.isBlank()) {
            return Paths.get(projectRootConfig).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Path resolveParserScript(Path projectRoot) {
        if (parserScriptConfig != null && !parserScriptConfig.isBlank()) {
            Path p = Paths.get(parserScriptConfig);
            return p.isAbsolute() ? p : projectRoot.resolve(parserScriptConfig).normalize();
        }
        Path inDoc = projectRoot.resolve("document-service").resolve("parse_to_dir.py").normalize();
        if (Files.exists(inDoc)) return inDoc;
        return projectRoot.resolve("parse_to_dir.py").normalize();
    }

    public Path userDataDir(String userId) {
        return resolveProjectRoot().resolve(USERDATA).resolve(userId);
    }

    public Path userPdfDir(String userId) {
        return userDataDir(userId).resolve(PDF_DIR);
    }

    public Path userImagesDir(String userId) {
        return userDataDir(userId).resolve(IMAGES_DIR);
    }

    public Path userPptDir(String userId) {
        return userDataDir(userId).resolve(PPT_DIR);
    }

    private Path pptIdDir(String userId, String pptId) {
        return userPptDir(userId).resolve(pptId != null ? pptId : "");
    }

    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /**
     * 上传 PDF：保存到 Userdata/{userId}/PDF/{fileId}/ 下，文件名为原文件名。
     * 若 parse=true，调用 Python 解析，生成 content.md 和 images 文件夹。
     */
    public String uploadPdf(String userId, MultipartFile file, boolean parse) throws IOException {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请上传 PDF 文件");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 PDF 文件");
        }

        String fileId = UUID.randomUUID().toString().replace("-", "");
        Path userPdf = userPdfDir(userId);
        Path fileDir = userPdf.resolve(fileId);
        Files.createDirectories(fileDir);

        Path pdfPath = fileDir.resolve(sanitizeFileName(name));
        file.transferTo(pdfPath.toFile());
        writeParseStatus(fileDir, PARSE_STATUS_NOT_PARSED);

        // 上传时勾选“解析”由调用方在后台异步执行，此处不再同步调用，避免长时间占用连接
        if (parse) {
            writeParseStatus(fileDir, PARSE_STATUS_PARSING);
        }

        return fileId;
    }

    /**
     * 上传图片：保存到 Userdata/{userId}/Images/{imageId}/，含图片文件与 info.json（fileName、description）。
     * 每次仅一张；描述由调用方传入，或 autoCaption=true 时调用 Python 生成。
     */
    public String uploadImage(String userId, MultipartFile file, String description, boolean autoCaption) throws IOException {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请上传图片文件");
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("仅支持图片格式：JPEG、PNG、GIF、WebP");
        }
        if ((description == null || description.isBlank()) && !autoCaption) {
            throw new IllegalArgumentException("请填写图片描述或勾选 AI 自动生成描述");
        }

        String imageId = UUID.randomUUID().toString().replace("-", "");
        Path imagesRoot = userImagesDir(userId);
        Path imageDir = imagesRoot.resolve(imageId);
        Files.createDirectories(imageDir);

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) originalName = "image";
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) ext = originalName.substring(dot);
        if (ext.isEmpty()) ext = ".jpg";
        String imageFileName = "image" + ext;
        Path imagePath = imageDir.resolve(imageFileName);
        file.transferTo(imagePath.toFile());

        String desc = (description != null && !description.isBlank()) ? description : "";
        if (autoCaption && desc.isEmpty()) {
            try {
                String cap = runCaptionForImage(imagePath);
                if (cap != null && !cap.isBlank()) desc = cap.trim();
            } catch (Exception e) {
                log.warn("AI 生成图片描述失败: {}", e.getMessage());
            }
        }
        writeImageInfo(imageDir, originalName, desc);
        return imageId;
    }

    private void writeImageInfo(Path imageDir, String fileName, String description) throws IOException {
        Path f = imageDir.resolve(IMAGE_INFO_FILE);
        String json = "{\"fileName\":\"" + escapeJson(fileName) + "\",\"description\":\"" + escapeJson(description) + "\"}";
        Files.writeString(f, json, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String runCaptionForImage(Path imagePath) throws IOException {
        Path projectRoot = resolveProjectRoot();
        Path script = resolveCaptionScript(projectRoot);
        if (!Files.exists(script)) {
            log.warn("未找到 caption_single.py，跳过 AI 描述: {}", script);
            return "";
        }
        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                script.toString(),
                "--image", imagePath.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Path workDir = projectRoot.resolve("document-service").toAbsolutePath().normalize();
        if (!Files.isDirectory(workDir)) workDir = projectRoot;
        pb.directory(workDir.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("caption_single.py 退出码 {}: {}", exit, out);
                return "";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        return out;
    }

    private Path resolveCaptionScript(Path projectRoot) {
        if (captionScriptConfig != null && !captionScriptConfig.isBlank()) {
            Path p = Paths.get(captionScriptConfig);
            return p.isAbsolute() ? p : projectRoot.resolve(captionScriptConfig).normalize();
        }
        Path inDoc = projectRoot.resolve("document-service").resolve("caption_single.py").normalize();
        if (Files.exists(inDoc)) return inDoc;
        return projectRoot.resolve("caption_single.py").normalize();
    }

    /** 列出用户 PDF 列表：每个项含 fileId、原 PDF 名、解析状态 0/1/2。 */
    public List<PdfEntry> listPdfs(String userId) throws IOException {
        if (userId == null || userId.isBlank()) return List.of();
        Path pdfDir = userPdfDir(userId);
        if (!Files.exists(pdfDir)) return List.of();
        List<PdfEntry> list = new ArrayList<>();
        try (Stream<Path> stream = Files.list(pdfDir)) {
            for (Path dir : stream.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String fileId = dir.getFileName().toString();
                String pdfName = null;
                for (Path f : list(dir)) {
                    if (f.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                        pdfName = f.getFileName().toString();
                        break;
                    }
                }
                int status = readParseStatus(dir);
                list.add(new PdfEntry(fileId, pdfName, status));
            }
        }
        return list;
    }

    /** 列出用户图片：每个项为 imageId、fileName、description。返回后调用方可打印一次上传的图片描述。 */
    public List<ImageEntry> listImages(String userId) throws IOException {
        if (userId == null || userId.isBlank()) return List.of();
        Path imagesRoot = userImagesDir(userId);
        if (!Files.exists(imagesRoot)) return List.of();
        List<ImageEntry> list = new ArrayList<>();
        try (Stream<Path> stream = Files.list(imagesRoot)) {
            for (Path dir : stream.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String imageId = dir.getFileName().toString();
                ImageInfo info = readImageInfo(dir);
                if (info == null) continue;
                list.add(new ImageEntry(imageId, info.fileName, info.description));
            }
        }
        if (!list.isEmpty()) {
            log.info("上传的图片描述: {}", list.stream().map(e -> e.imageId + "=" + e.description).toList());
        }
        return list;
    }

    /** 列出指定 PDF 解析出的图片（来自 PDF/{fileId}/images/info.json）：id、description。 */
    public List<PdfImageEntry> listPdfImages(String userId, String fileId) throws IOException {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) return List.of();
        Path imagesDir = userPdfDir(userId).resolve(fileId).resolve(IMAGES_FOLDER);
        Path infoJson = imagesDir.resolve("info.json");
        if (!Files.exists(infoJson)) return List.of();
        try {
            String json = Files.readString(infoJson, StandardCharsets.UTF_8).trim();
            JsonNode arr = new ObjectMapper().readTree(json);
            if (arr == null || !arr.isArray()) return List.of();
            List<PdfImageEntry> list = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.has("id") && n.has("description")) {
                    list.add(new PdfImageEntry(n.get("id").asText(), n.get("description").asText()));
                }
            }
            return list;
        } catch (Exception e) {
            log.debug("读取 PDF images info.json 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 获取 PDF 内单张图片文件路径，用于预览；不存在则返回 null。 */
    public Path getPdfImageFilePath(String userId, String fileId, String imageId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank() || imageId == null || imageId.isBlank()) return null;
        Path p = userPdfDir(userId).resolve(fileId).resolve(IMAGES_FOLDER).resolve(imageId);
        return (Files.exists(p) && !Files.isDirectory(p)) ? p : null;
    }

    private static class ImageInfo {
        String fileName;
        String description;
    }

    private ImageInfo readImageInfo(Path imageDir) {
        Path f = imageDir.resolve(IMAGE_INFO_FILE);
        if (!Files.exists(f)) return null;
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8).trim();
            String fileName = extractJsonString(s, "fileName");
            String description = extractJsonString(s, "description");
            if (fileName == null) fileName = "";
            if (description == null) description = "";
            ImageInfo info = new ImageInfo();
            info.fileName = fileName;
            info.description = description;
            return info;
        } catch (Exception e) {
            log.debug("读取 info.json 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < json.length()) {
                end += 2;
                continue;
            }
            if (c == '"') break;
            end++;
        }
        if (end > json.length()) return null;
        String raw = json.substring(start, end);
        return raw.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** 删除图片：删除整个目录 Userdata/{userId}/Images/{imageId}/。 */
    public void deleteImage(String userId, String imageId) throws IOException {
        if (userId == null || userId.isBlank() || imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("userId 与 imageId 不能为空");
        }
        Path imageDir = userImagesDir(userId).resolve(imageId);
        if (!Files.exists(imageDir) || !Files.isDirectory(imageDir)) {
            throw new IllegalArgumentException("图片记录不存在: " + imageId);
        }
        try (Stream<Path> walk = Files.walk(imageDir, FileVisitOption.FOLLOW_LINKS)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** 更新图片描述。 */
    public void updateImageDescription(String userId, String imageId, String description) throws IOException {
        if (userId == null || userId.isBlank() || imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("userId 与 imageId 不能为空");
        }
        Path imageDir = userImagesDir(userId).resolve(imageId);
        if (!Files.exists(imageDir) || !Files.isDirectory(imageDir)) {
            throw new IllegalArgumentException("图片记录不存在: " + imageId);
        }
        ImageInfo info = readImageInfo(imageDir);
        String fileName = (info != null && info.fileName != null) ? info.fileName : "image";
        writeImageInfo(imageDir, fileName, description != null ? description : "");
    }

    /** 获取图片文件路径，用于预览；不存在则返回 null。 */
    public Path getImageFilePath(String userId, String imageId) {
        if (userId == null || userId.isBlank() || imageId == null || imageId.isBlank()) return null;
        Path imageDir = userImagesDir(userId).resolve(imageId);
        if (!Files.exists(imageDir) || !Files.isDirectory(imageDir)) return null;
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".gif", ".webp")) {
            Path p = imageDir.resolve("image" + ext);
            if (Files.exists(p)) return p;
        }
        try {
            for (Path p : list(imageDir)) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".webp")) {
                    return p;
                }
            }
        } catch (IOException e) {
            log.debug("list image dir: {}", e.getMessage());
        }
        return null;
    }

    public record ImageEntry(String imageId, String fileName, String description) {}

    public record PdfImageEntry(String id, String description) {}

    /** 获取 PDF 目录下的 content.md 内容；无则返回空字符串。 */
    public String getPdfContentMd(String userId, String fileId) throws IOException {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) return "";
        Path md = userPdfDir(userId).resolve(fileId).resolve(CONTENT_MD);
        if (!Files.exists(md)) return "";
        return Files.readString(md, StandardCharsets.UTF_8);
    }

    /** 获取 PDF 原文件路径，用于预览/下载；不存在则返回 null。 */
    public Path getPdfFilePath(String userId, String fileId) throws IOException {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) return null;
        Path fileDir = userPdfDir(userId).resolve(fileId);
        if (!Files.exists(fileDir) || !Files.isDirectory(fileDir)) return null;
        for (Path p : list(fileDir)) {
            if (p.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                return p;
            }
        }
        return null;
    }

    /** 删除 PDF：删除整个目录 Userdata/{userId}/PDF/{fileId}/（含 PDF、content.md、images、parse-status.json）。 */
    public void deletePdf(String userId, String fileId) throws IOException {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("userId 与 fileId 不能为空");
        }
        Path fileDir = userPdfDir(userId).resolve(fileId);
        if (!Files.exists(fileDir) || !Files.isDirectory(fileDir)) {
            throw new IllegalArgumentException("PDF 记录不存在: " + fileId);
        }
        try (Stream<Path> walk = Files.walk(fileDir, FileVisitOption.FOLLOW_LINKS)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** 对已上传的 PDF 执行解析，写入 content.md 和 images。 */
    public void parsePdf(String userId, String fileId) throws IOException {
        Path fileDir = userPdfDir(userId).resolve(fileId);
        if (!Files.exists(fileDir) || !Files.isDirectory(fileDir)) {
            throw new IllegalArgumentException("PDF 记录不存在: " + fileId);
        }
        Path pdfPath = null;
        for (Path p : list(fileDir)) {
            if (p.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                pdfPath = p;
                break;
            }
        }
        if (pdfPath == null || !Files.exists(pdfPath)) {
            throw new IllegalArgumentException("该目录下未找到 PDF 文件");
        }
        writeParseStatus(fileDir, PARSE_STATUS_PARSING);
        try {
            runParseToDir(pdfPath, fileDir);
            writeParseStatus(fileDir, PARSE_STATUS_PARSED);
        } catch (Exception e) {
            writeParseStatus(fileDir, PARSE_STATUS_NOT_PARSED);
            throw e;
        }
    }

    private void runParseToDir(Path pdfPath, Path outputDir) throws IOException {
        Path projectRoot = resolveProjectRoot();
        Path script = resolveParserScript(projectRoot);
        if (!Files.exists(script)) {
            log.warn("未找到 parse_to_dir.py，跳过解析: {}", script);
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                script.toString(),
                "--pdf", pdfPath.toAbsolutePath().toString(),
                "--output-dir", outputDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        pb.directory(projectRoot.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("parse_to_dir.py 退出码 {}: {}", exit, out);
                throw new IOException("PDF 解析失败: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解析被中断", e);
        }
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "file";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void writeParseStatus(Path fileDir, int status) throws IOException {
        Path f = fileDir.resolve(PARSE_STATUS_FILE);
        Files.writeString(f, "{\"status\":" + status + "}", StandardCharsets.UTF_8);
    }

    private int readParseStatus(Path fileDir) {
        Path f = fileDir.resolve(PARSE_STATUS_FILE);
        if (!Files.exists(f)) {
            boolean hasMd = Files.exists(fileDir.resolve(CONTENT_MD));
            return hasMd ? PARSE_STATUS_PARSED : PARSE_STATUS_NOT_PARSED;
        }
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8).trim();
            int start = s.indexOf("\"status\":");
            if (start >= 0) {
                start += 9;
                int end = s.indexOf("}", start);
                if (end < 0) end = s.length();
                int v = Integer.parseInt(s.substring(start, end).trim());
                if (v >= 0 && v <= 2) return v;
            }
        } catch (Exception e) {
            log.debug("读取 parse-status.json 失败: {}", e.getMessage());
        }
        return PARSE_STATUS_NOT_PARSED;
    }

    /** 获取指定 PDF 的解析状态（供异步解析前校验用）。 */
    public int getParseStatus(String userId, String fileId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) return PARSE_STATUS_NOT_PARSED;
        Path fileDir = userPdfDir(userId).resolve(fileId);
        if (!Files.exists(fileDir) || !Files.isDirectory(fileDir)) return PARSE_STATUS_NOT_PARSED;
        return readParseStatus(fileDir);
    }

    /** 设置指定 PDF 的解析状态（供异步解析立即写入“解析中”用）。 */
    public void setParseStatus(String userId, String fileId, int status) throws IOException {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("userId 与 fileId 不能为空");
        }
        Path fileDir = userPdfDir(userId).resolve(fileId);
        if (!Files.exists(fileDir) || !Files.isDirectory(fileDir)) {
            throw new IllegalArgumentException("PDF 记录不存在: " + fileId);
        }
        writeParseStatus(fileDir, status);
    }

    // --------------- PPT 创作持久化（Userdata/{userId}/ppt/{pptId}/） ---------------

    /** 列表项：ppt/index.json 及列表接口返回 */
    public record PptListItem(String pptId, String topic, int currentStep, String updatedAt) {}
    /** process.json */
    public record PptProcess(int currentStep, String topic, String updatedAt) {}
    /** upload.json */
    public record PptUpload(List<String> pdfIds, List<String> imageIds) {
        public PptUpload() { this(List.of(), List.of()); }
    }
    /** outline.json */
    public record PptOutline(String content) {
        public PptOutline() { this(""); }
    }
    /** 单页内容（pageContents.json 内一项） */
    public record PptPageItem(String theme, String textContent, List<String> imageIds, String pageType) {}
    /** pageContents.json */
    public record PptPageContents(List<PptPageItem> pages) {
        public PptPageContents() { this(List.of()); }
    }
    /** layoutCodes.json */
    public record PptLayoutCodes(List<String> codes) {
        public PptLayoutCodes() { this(List.of()); }
    }
    /** style.json */
    public record PptStyle(String styleId) {
        public PptStyle() { this(""); }
    }
    /** 逐页生成单页项（generatedPages.json 内一项，仅存 id 与 html） */
    public record PptGeneratedPageItem(int id, String html) {}
    /** generatedPages.json：逐页生成的 HTML 列表 */
    public record PptGeneratedPages(List<PptGeneratedPageItem> pages) {
        public PptGeneratedPages() { this(List.of()); }
    }
    /** 完整状态（获取单份创作时返回） */
    public record PptState(PptProcess process, PptUpload upload, PptOutline outline, PptPageContents pageContents, PptLayoutCodes layoutCodes, PptStyle style, PptGeneratedPages generatedPages) {}

    private Path ensurePptDir(String userId, String pptId) throws IOException {
        Path dir = pptIdDir(userId, pptId);
        Files.createDirectories(dir);
        return dir;
    }

    private List<PptListItem> readPptIndex(Path userPpt) throws IOException {
        Path index = userPpt.resolve(PPT_INDEX_JSON);
        if (!Files.exists(index)) return new ArrayList<>();
        String json = Files.readString(index, StandardCharsets.UTF_8).trim();
        if (json.isBlank()) return new ArrayList<>();
        try {
            JsonNode root = PPT_MAPPER.readTree(json);
            JsonNode items = root != null ? root.get("items") : null;
            if (items == null || !items.isArray()) return new ArrayList<>();
            List<PptListItem> list = new ArrayList<>();
            for (JsonNode n : items) {
                String id = n.has("pptId") ? n.get("pptId").asText() : null;
                String topic = n.has("topic") ? n.get("topic").asText() : "";
                int step = n.has("currentStep") ? n.get("currentStep").asInt(0) : 0;
                String updatedAt = n.has("updatedAt") ? n.get("updatedAt").asText() : "";
                if (id != null && !id.isBlank()) list.add(new PptListItem(id, topic, step, updatedAt));
            }
            return list;
        } catch (Exception e) {
            log.debug("读取 ppt index.json 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writePptIndex(Path userPpt, List<PptListItem> items) throws IOException {
        Path index = userPpt.resolve(PPT_INDEX_JSON);
        String json = PPT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("items", items));
        Files.writeString(index, json, StandardCharsets.UTF_8);
    }

    /** 创建一份 PPT 创作（内容上传完成后）：生成 pptId，写入 process.json、upload.json，并加入 index。 */
    public String createPpt(String userId, String topic, List<String> pdfIds, List<String> imageIds) throws IOException {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空");
        String pptId = UUID.randomUUID().toString().replace("-", "");
        Path userPpt = userPptDir(userId);
        Files.createDirectories(userPpt);
        Path dir = ensurePptDir(userId, pptId);
        String now = java.time.Instant.now().toString();
        PptProcess process = new PptProcess(0, topic != null ? topic : "", now);
        PptUpload upload = new PptUpload(pdfIds != null ? pdfIds : List.of(), imageIds != null ? imageIds : List.of());
        Files.writeString(dir.resolve(PROCESS_JSON), PPT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(process), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(UPLOAD_JSON), PPT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(upload), StandardCharsets.UTF_8);
        List<PptListItem> items = readPptIndex(userPpt);
        items.add(0, new PptListItem(pptId, process.topic(), process.currentStep(), process.updatedAt()));
        writePptIndex(userPpt, items);
        return pptId;
    }

    /** 列出该用户所有 PPT 创作（从 index.json 读取）。 */
    public List<PptListItem> listPpts(String userId) throws IOException {
        if (userId == null || userId.isBlank()) return List.of();
        Path userPpt = userPptDir(userId);
        if (!Files.exists(userPpt)) return List.of();
        return readPptIndex(userPpt);
    }

    /** 获取一份 PPT 创作的完整状态。 */
    public PptState getPpt(String userId, String pptId) throws IOException {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            throw new IllegalArgumentException("userId 与 pptId 不能为空");
        }
        Path dir = pptIdDir(userId, pptId);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("PPT 记录不存在: " + pptId);
        }
        PptProcess process = readJson(dir, PROCESS_JSON, PptProcess.class, new PptProcess(0, "", ""));
        PptUpload upload = readJson(dir, UPLOAD_JSON, PptUpload.class, new PptUpload());
        PptOutline outline = readJson(dir, OUTLINE_JSON, PptOutline.class, new PptOutline());
        PptPageContents pageContents = readJson(dir, PAGE_CONTENTS_JSON, PptPageContents.class, new PptPageContents());
        PptLayoutCodes layoutCodes = readJson(dir, LAYOUT_CODES_JSON, PptLayoutCodes.class, new PptLayoutCodes());
        PptStyle style = readJson(dir, STYLE_JSON, PptStyle.class, new PptStyle());
        PptGeneratedPages generatedPages = readJson(dir, GENERATED_PAGES_JSON, PptGeneratedPages.class, new PptGeneratedPages());
        return new PptState(process, upload, outline, pageContents, layoutCodes, style, generatedPages);
    }

    private <T> T readJson(Path dir, String fileName, Class<T> type, T defaultValue) {
        Path f = dir.resolve(fileName);
        if (!Files.exists(f)) return defaultValue;
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (json.isBlank()) return defaultValue;
            return PPT_MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.debug("读取 {} 失败: {}", fileName, e.getMessage());
            return defaultValue;
        }
    }

    private void writeJson(Path dir, String fileName, Object value) throws IOException {
        Path f = dir.resolve(fileName);
        String json = PPT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        Files.writeString(f, json, StandardCharsets.UTF_8);
    }

    private void updatePptIndexItem(String userId, String pptId, String topic, int currentStep, String updatedAt) throws IOException {
        Path userPpt = userPptDir(userId);
        if (!Files.exists(userPpt)) return;
        List<PptListItem> items = readPptIndex(userPpt);
        List<PptListItem> updated = new ArrayList<>();
        for (PptListItem it : items) {
            if (pptId.equals(it.pptId())) {
                updated.add(new PptListItem(it.pptId(), topic != null ? topic : it.topic(), currentStep, updatedAt != null ? updatedAt : it.updatedAt()));
            } else {
                updated.add(it);
            }
        }
        writePptIndex(userPpt, updated);
    }

    /** 更新 process.json（topic 为 null 时保留原值）。 */
    public void updatePptProcess(String userId, String pptId, int currentStep, String topic) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        String now = java.time.Instant.now().toString();
        PptProcess existing = readJson(dir, PROCESS_JSON, PptProcess.class, new PptProcess(0, "", ""));
        String topicToUse = (topic != null && !topic.isBlank()) ? topic : existing.topic();
        PptProcess process = new PptProcess(currentStep, topicToUse, now);
        writeJson(dir, PROCESS_JSON, process);
        updatePptIndexItem(userId, pptId, topicToUse, currentStep, now);
    }

    /** 更新 upload.json。 */
    public void updatePptUpload(String userId, String pptId, List<String> pdfIds, List<String> imageIds) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        writeJson(dir, UPLOAD_JSON, new PptUpload(pdfIds != null ? pdfIds : List.of(), imageIds != null ? imageIds : List.of()));
    }

    /** 更新 outline.json。 */
    public void updatePptOutline(String userId, String pptId, String content) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        writeJson(dir, OUTLINE_JSON, new PptOutline(content != null ? content : ""));
    }

    /** 更新 pageContents.json。 */
    public void updatePptPageContents(String userId, String pptId, List<PptPageItem> pages) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        writeJson(dir, PAGE_CONTENTS_JSON, new PptPageContents(pages != null ? pages : List.of()));
    }

    /** 更新 layoutCodes.json。 */
    public void updatePptLayoutCodes(String userId, String pptId, List<String> codes) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        writeJson(dir, LAYOUT_CODES_JSON, new PptLayoutCodes(codes != null ? codes : List.of()));
    }

    /** 更新 style.json。 */
    public void updatePptStyle(String userId, String pptId, String styleId) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        writeJson(dir, STYLE_JSON, new PptStyle(styleId != null ? styleId : ""));
    }

    /** 更新 generatedPages.json（逐页生成的 HTML 列表）。 */
    public void updatePptGeneratedPages(String userId, String pptId, List<PptGeneratedPageItem> pages) throws IOException {
        Path dir = ensurePptDir(userId, pptId);
        writeJson(dir, GENERATED_PAGES_JSON, new PptGeneratedPages(pages != null ? pages : List.of()));
    }

    /** 删除一份 PPT 创作：删除目录并从 index 中移除。 */
    public void deletePpt(String userId, String pptId) throws IOException {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            throw new IllegalArgumentException("userId 与 pptId 不能为空");
        }
        Path dir = pptIdDir(userId, pptId);
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Path userPpt = userPptDir(userId);
        if (Files.exists(userPpt)) {
            List<PptListItem> items = readPptIndex(userPpt);
            List<PptListItem> updated = items.stream().filter(it -> !pptId.equals(it.pptId())).toList();
            if (updated.size() != items.size()) {
                writePptIndex(userPpt, new ArrayList<>(updated));
            }
        }
    }

    /** parseStatus: 0=未解析，1=解析中，2=已解析 */
    public record PdfEntry(String fileId, String pdfFileName, int parseStatus) {}
}
