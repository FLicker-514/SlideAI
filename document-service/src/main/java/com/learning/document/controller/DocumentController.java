package com.learning.document.controller;

import com.learning.common.Result;
import com.learning.document.service.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 文档/文件管理：上传 PDF、图片；PDF 可选解析为 content.md + images。
 * 解析采用异步执行，接口立即返回 202，避免长连接超时与 Broken pipe。
 */
@Slf4j
@RestController
@RequestMapping("/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentStorageService documentStorageService;

    @Qualifier("pdfParseExecutor")
    private final ExecutorService pdfParseExecutor;

    /** 上传 PDF；parse=true 时解析并生成 content.md、images */
    @PostMapping(value = "/upload/pdf", consumes = "multipart/form-data")
    public Result<Map<String, Object>> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId,
            @RequestParam(value = "parse", defaultValue = "false") boolean parse) {
        if (userId == null || userId.isBlank()) {
            return Result.error(400, "userId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传 PDF 文件");
        }
        try {
            String fileId = documentStorageService.uploadPdf(userId, file, parse);
            if (parse) {
                String uid = userId;
                String fid = fileId;
                pdfParseExecutor.submit(() -> {
                    try {
                        documentStorageService.parsePdf(uid, fid);
                    } catch (Exception e) {
                        log.error("上传后后台解析 PDF 失败 userId={} fileId={}", uid, fid, e);
                    }
                });
            }
            return Result.success(Map.of(
                    "fileId", fileId,
                    "parsed", parse
            ));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("上传/解析 PDF 失败", e);
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    /** 上传图片（每次一张）；须填描述或勾选 AI 自动生成，保存到 Userdata/{userId}/Images/{imageId}/ */
    @PostMapping(value = "/upload/image", consumes = "multipart/form-data")
    public Result<Map<String, String>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "autoCaption", defaultValue = "false") boolean autoCaption) {
        if (userId == null || userId.isBlank()) {
            return Result.error(400, "userId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传图片文件");
        }
        if ((description == null || description.isBlank()) && !autoCaption) {
            return Result.error(400, "请填写图片描述或勾选 AI 自动生成描述");
        }
        try {
            String imageId = documentStorageService.uploadImage(userId, file, description != null ? description : "", autoCaption);
            return Result.success(Map.of("imageId", imageId));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("上传图片失败", e);
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    /** 列出用户 PDF 列表 */
    @GetMapping("/list/pdf")
    public Result<List<DocumentStorageService.PdfEntry>> listPdfs(@RequestParam("userId") String userId) {
        if (userId == null || userId.isBlank()) {
            return Result.success(List.of());
        }
        try {
            return Result.success(documentStorageService.listPdfs(userId));
        } catch (IOException e) {
            log.error("列出 PDF 失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 列出用户图片列表（imageId、fileName、description） */
    @GetMapping("/list/image")
    public Result<List<DocumentStorageService.ImageEntry>> listImages(@RequestParam("userId") String userId) {
        if (userId == null || userId.isBlank()) {
            return Result.success(List.of());
        }
        try {
            return Result.success(documentStorageService.listImages(userId));
        } catch (IOException e) {
            log.error("列出图片失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 预览/下载图片原文件 */
    @GetMapping("/image/file")
    public ResponseEntity<Resource> getImageFile(
            @RequestParam("userId") String userId,
            @RequestParam("imageId") String imageId) {
        if (userId == null || userId.isBlank() || imageId == null || imageId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Path imagePath = documentStorageService.getImageFilePath(userId, imageId);
        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(imagePath);
        String rawName = imagePath.getFileName().toString();
        String asciiFallback = rawName.replaceAll("[^\\x00-\\x7F]", "_").replace("\"", "");
        if (asciiFallback.isBlank()) asciiFallback = "image";
        String encodedName = URLEncoder.encode(rawName, StandardCharsets.UTF_8).replace("+", "%20");
        String contentDisposition = "inline; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encodedName;
        String contentType = "image/jpeg";
        String lower = rawName.toLowerCase();
        if (lower.endsWith(".png")) contentType = "image/png";
        else if (lower.endsWith(".gif")) contentType = "image/gif";
        else if (lower.endsWith(".webp")) contentType = "image/webp";
        return ResponseEntity.ok()
                .header("Content-Disposition", contentDisposition)
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    /** 删除图片（同时删除本地目录） */
    @DeleteMapping("/image")
    public Result<Void> deleteImage(
            @RequestParam("userId") String userId,
            @RequestParam("imageId") String imageId) {
        if (userId == null || userId.isBlank() || imageId == null || imageId.isBlank()) {
            return Result.error(400, "userId 与 imageId 不能为空");
        }
        try {
            documentStorageService.deleteImage(userId, imageId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("删除图片失败", e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    /** 更新图片描述 */
    @PutMapping("/image/description")
    public Result<Void> updateImageDescription(
            @RequestParam("userId") String userId,
            @RequestParam("imageId") String imageId,
            @RequestParam("description") String description) {
        if (userId == null || userId.isBlank() || imageId == null || imageId.isBlank()) {
            return Result.error(400, "userId 与 imageId 不能为空");
        }
        try {
            documentStorageService.updateImageDescription(userId, imageId, description != null ? description : "");
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("更新图片描述失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 列出指定 PDF 解析出的图片（id、description，来自 images/info.json） */
    @GetMapping("/pdf/images")
    public Result<List<DocumentStorageService.PdfImageEntry>> listPdfImages(
            @RequestParam("userId") String userId,
            @RequestParam("fileId") String fileId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            return Result.success(List.of());
        }
        try {
            return Result.success(documentStorageService.listPdfImages(userId, fileId));
        } catch (IOException e) {
            log.error("列出 PDF 图片失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 预览/下载 PDF 内单张图片文件 */
    @GetMapping("/pdf/image/file")
    public ResponseEntity<Resource> getPdfImageFile(
            @RequestParam("userId") String userId,
            @RequestParam("fileId") String fileId,
            @RequestParam("imageId") String imageId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank() || imageId == null || imageId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Path imagePath = documentStorageService.getPdfImageFilePath(userId, fileId, imageId);
        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(imagePath);
        String rawName = imagePath.getFileName().toString();
        String asciiFallback = rawName.replaceAll("[^\\x00-\\x7F]", "_").replace("\"", "");
        if (asciiFallback.isBlank()) asciiFallback = "image";
        String encodedName = URLEncoder.encode(rawName, StandardCharsets.UTF_8).replace("+", "%20");
        String contentDisposition = "inline; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encodedName;
        String contentType = "image/jpeg";
        String lower = rawName.toLowerCase();
        if (lower.endsWith(".png")) contentType = "image/png";
        else if (lower.endsWith(".gif")) contentType = "image/gif";
        else if (lower.endsWith(".webp")) contentType = "image/webp";
        return ResponseEntity.ok()
                .header("Content-Disposition", contentDisposition)
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    /** 获取 PDF 解析内容 content.md */
    @GetMapping("/pdf/content")
    public Result<Map<String, String>> getPdfContent(
            @RequestParam("userId") String userId,
            @RequestParam("fileId") String fileId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            return Result.error(400, "userId 与 fileId 不能为空");
        }
        try {
            String content = documentStorageService.getPdfContentMd(userId, fileId);
            return Result.success(Map.of("content", content != null ? content : ""));
        } catch (IOException e) {
            log.error("读取 content.md 失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 预览/下载 PDF 原文件（inline 展示） */
    @GetMapping("/pdf/file")
    public ResponseEntity<Resource> getPdfFile(
            @RequestParam("userId") String userId,
            @RequestParam("fileId") String fileId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Path pdfPath = documentStorageService.getPdfFilePath(userId, fileId);
            if (pdfPath == null || !Files.exists(pdfPath)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new FileSystemResource(pdfPath);
            String rawName = pdfPath.getFileName().toString();
            String asciiFallback = rawName.replaceAll("[^\\x00-\\x7F]", "_").replace("\"", "");
            if (asciiFallback.isBlank()) asciiFallback = "file.pdf";
            String encodedName = URLEncoder.encode(rawName, StandardCharsets.UTF_8).replace("+", "%20");
            String contentDisposition = "inline; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encodedName;
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", contentDisposition)
                    .body(resource);
        } catch (IOException e) {
            log.error("读取 PDF 文件失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 对已上传的 PDF 执行解析（异步：立即返回 202，后台执行，避免长连接超时） */
    @PostMapping("/pdf/parse")
    public Result<Void> parsePdf(
            @RequestParam("userId") String userId,
            @RequestParam("fileId") String fileId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            return Result.error(400, "userId 与 fileId 不能为空");
        }
        try {
            if (documentStorageService.getParseStatus(userId, fileId) == DocumentStorageService.PARSE_STATUS_PARSING) {
                return Result.error(409, "该文件正在解析中，请勿重复提交");
            }
            documentStorageService.setParseStatus(userId, fileId, DocumentStorageService.PARSE_STATUS_PARSING);
            String uid = userId;
            String fid = fileId;
            pdfParseExecutor.submit(() -> {
                try {
                    documentStorageService.parsePdf(uid, fid);
                } catch (Exception e) {
                    log.error("后台解析 PDF 失败 userId={} fileId={}", uid, fid, e);
                }
            });
            return Result.<Void>builder()
                    .code(202)
                    .message("解析已开始，请刷新列表查看进度")
                    .data(null)
                    .build();
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("设置解析状态失败", e);
            return Result.error(500, "解析启动失败: " + e.getMessage());
        }
    }

    /** 删除 PDF（同时删除本地目录及其中所有文件） */
    @DeleteMapping("/pdf")
    public Result<Void> deletePdf(
            @RequestParam("userId") String userId,
            @RequestParam("fileId") String fileId) {
        if (userId == null || userId.isBlank() || fileId == null || fileId.isBlank()) {
            return Result.error(400, "userId 与 fileId 不能为空");
        }
        try {
            documentStorageService.deletePdf(userId, fileId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("删除 PDF 失败", e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    // --------------- PPT 创作（Userdata/{userId}/ppt/{pptId}/） ---------------

    /** 创建一份 PPT 创作（内容上传完成后），返回 pptId */
    @PostMapping("/ppt")
    public Result<Map<String, String>> createPpt(
            @RequestParam("userId") String userId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank()) {
            return Result.error(400, "userId 不能为空");
        }
        String topic = body != null && body.get("topic") != null ? body.get("topic").toString() : "";
        List<String> pdfIds = toStringList(body != null ? body.get("pdfIds") : null);
        List<String> imageIds = toStringList(body != null ? body.get("imageIds") : null);
        try {
            String pptId = documentStorageService.createPpt(userId, topic, pdfIds, imageIds);
            return Result.success(Map.of("pptId", pptId));
        } catch (IOException e) {
            log.error("创建 PPT 创作失败", e);
            return Result.error(500, "创建失败: " + e.getMessage());
        }
    }

    /** 列出该用户所有 PPT 创作（用于「继续做」） */
    @GetMapping("/ppt/list")
    public Result<List<DocumentStorageService.PptListItem>> listPpts(@RequestParam("userId") String userId) {
        if (userId == null || userId.isBlank()) {
            return Result.success(List.of());
        }
        try {
            return Result.success(documentStorageService.listPpts(userId));
        } catch (IOException e) {
            log.error("列出 PPT 创作失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 获取一份 PPT 创作的完整状态（用于恢复工坊） */
    @GetMapping("/ppt/{pptId}")
    public Result<DocumentStorageService.PptState> getPpt(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        try {
            return Result.success(documentStorageService.getPpt(userId, pptId));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("获取 PPT 创作失败", e);
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /** 更新 process.json（当前步骤、主题） */
    @PutMapping("/ppt/{pptId}/process")
    public Result<Void> updatePptProcess(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        int currentStep = body != null && body.get("currentStep") != null
                ? ((Number) body.get("currentStep")).intValue()
                : 0;
        String topic = body != null && body.get("topic") != null ? body.get("topic").toString() : null;
        try {
            documentStorageService.updatePptProcess(userId, pptId, currentStep, topic);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT process 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 更新 upload.json */
    @PutMapping("/ppt/{pptId}/upload")
    public Result<Void> updatePptUpload(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        List<String> pdfIds = toStringList(body != null ? body.get("pdfIds") : null);
        List<String> imageIds = toStringList(body != null ? body.get("imageIds") : null);
        try {
            documentStorageService.updatePptUpload(userId, pptId, pdfIds, imageIds);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT upload 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 更新 outline.json */
    @PutMapping("/ppt/{pptId}/outline")
    public Result<Void> updatePptOutline(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        String content = body != null && body.get("content") != null ? body.get("content").toString() : "";
        try {
            documentStorageService.updatePptOutline(userId, pptId, content);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT outline 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 更新 pageContents.json */
    @PutMapping("/ppt/{pptId}/page-contents")
    public Result<Void> updatePptPageContents(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        List<DocumentStorageService.PptPageItem> pages = List.of();
        if (body != null && body.get("pages") instanceof List<?> list) {
            pages = list.stream().map(item -> {
                if (!(item instanceof Map)) return null;
                Map<?, ?> m = (Map<?, ?>) item;
                String theme = m.get("theme") != null ? m.get("theme").toString() : "";
                String textContent = m.get("textContent") != null ? m.get("textContent").toString() : "";
                String pageType = m.get("pageType") != null ? m.get("pageType").toString() : "";
                @SuppressWarnings("unchecked")
                List<String> imageIds = m.get("imageIds") instanceof List ? (List<String>) m.get("imageIds") : List.of();
                return new DocumentStorageService.PptPageItem(theme, textContent, imageIds, pageType);
            }).filter(x -> x != null).collect(Collectors.toList());
        }
        try {
            documentStorageService.updatePptPageContents(userId, pptId, pages);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT pageContents 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 更新 layoutCodes.json */
    @PutMapping("/ppt/{pptId}/layout-codes")
    public Result<Void> updatePptLayoutCodes(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        @SuppressWarnings("unchecked")
        List<String> codes = body != null && body.get("codes") instanceof List ? (List<String>) body.get("codes") : List.of();
        try {
            documentStorageService.updatePptLayoutCodes(userId, pptId, codes);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT layoutCodes 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 删除一份 PPT 创作 */
    @DeleteMapping("/ppt/{pptId}")
    public Result<Void> deletePpt(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        try {
            documentStorageService.deletePpt(userId, pptId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("删除 PPT 创作失败", e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    /** 更新 style.json */
    @PutMapping("/ppt/{pptId}/style")
    public Result<Void> updatePptStyle(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        String styleId = body != null && body.get("styleId") != null ? body.get("styleId").toString() : "";
        try {
            documentStorageService.updatePptStyle(userId, pptId, styleId);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT style 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 更新 generatedPages.json（逐页生成结果） */
    @PutMapping("/ppt/{pptId}/generated-pages")
    public Result<Void> updatePptGeneratedPages(
            @RequestParam("userId") String userId,
            @PathVariable("pptId") String pptId,
            @RequestBody Map<String, Object> body) {
        if (userId == null || userId.isBlank() || pptId == null || pptId.isBlank()) {
            return Result.error(400, "userId 与 pptId 不能为空");
        }
        List<DocumentStorageService.PptGeneratedPageItem> pages = List.of();
        if (body != null && body.get("pages") instanceof List<?> list) {
            pages = list.stream().map(item -> {
                if (!(item instanceof Map)) return null;
                Map<?, ?> m = (Map<?, ?>) item;
                int id = m.get("id") instanceof Number ? ((Number) m.get("id")).intValue() : 0;
                String html = m.get("html") != null ? m.get("html").toString() : "";
                return new DocumentStorageService.PptGeneratedPageItem(id, html);
            }).filter(x -> x != null).collect(Collectors.toList());
        }
        try {
            documentStorageService.updatePptGeneratedPages(userId, pptId, pages);
            return Result.success(null);
        } catch (IOException e) {
            log.error("更新 PPT generatedPages 失败", e);
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /** 将 JSON body 中的 pdfIds/imageIds 转为 List&lt;String&gt;，兼容多种序列化结果 */
    private static List<String> toStringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream()
                    .map(x -> x != null ? x.toString().trim() : "")
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
