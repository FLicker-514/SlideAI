package com.learning.outline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.outline.dto.OutlineRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutlinePythonService {

    private final ObjectMapper objectMapper;

    @Value("${outline.python.command:python3}")
    private String pythonCommand;

    @Value("${outline.python.workspace:outline-service}")
    private String workspaceConfig;

    @Value("${outline.timeout-seconds:120}")
    private int timeoutSeconds;

    public String generateOutline(OutlineRequest request) throws IOException, InterruptedException {
        Path workspace = resolveWorkspace();
        Map<String, Object> input = new HashMap<>();
        input.put("topic", request.getTopic() != null ? request.getTopic() : "");
        input.put("documentContents", request.getDocumentContents() != null ? request.getDocumentContents() : List.of());
        input.put("language", StringUtils.hasText(request.getLanguage()) ? request.getLanguage() : "zh");
        input.put("extraRequirements", request.getExtraRequirements() != null ? request.getExtraRequirements() : "");
        String inputJson = objectMapper.writeValueAsString(input);

        List<String> docs = request.getDocumentContents() != null ? request.getDocumentContents() : List.of();
        log.info("[outline] 发给 Python 的请求: topic={}, documentContents 数量={}, 各段长度={}",
                request.getTopic(),
                docs.size(),
                docs.stream().map(d -> d != null ? d.length() : 0).toList());
        if (!docs.isEmpty()) {
            for (int i = 0; i < docs.size(); i++) {
                String d = docs.get(i);
                int len = d != null ? d.length() : 0;
                String preview = d != null && d.length() > 0 ? d.substring(0, Math.min(120, d.length())).replace("\n", " ") + "..." : "(空)";
                log.info("[outline] 文档{} 长度={} 首段预览: {}", i + 1, len, preview);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(pythonCommand, "-m", "outline_service.generate_cli");
        pb.redirectErrorStream(false);
        pb.directory(workspace.toFile());
        pb.environment().put("PYTHONPATH", workspace.toAbsolutePath().toString());

        Process p = pb.start();
        try {
            p.getOutputStream().write((inputJson + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
        } catch (IOException e) {
            p.destroyForcibly();
            throw e;
        }

        Thread errReader = new Thread(() -> drainStream(p.getErrorStream()), "outline-stderr");
        errReader.setDaemon(true);
        errReader.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Python outline generation timeout");
        }
        if (p.exitValue() != 0) {
            log.warn("outline_service.generate_cli exit {}: {}", p.exitValue(), output);
            throw new IOException("Outline generation failed: " + output);
        }

        JsonNode node = objectMapper.readTree(output);
        if (node.isObject() && node.has("error")) {
            throw new IOException("Outline error: " + node.get("error").asText());
        }
        return output;
    }

    private Path resolveWorkspace() {
        String s = workspaceConfig != null ? workspaceConfig.trim() : "";
        Path p = s.isEmpty() ? Paths.get("outline-service") : Paths.get(s);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p).toAbsolutePath().normalize();
        }
        if (!Files.isDirectory(p)) {
            Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            if (Files.isDirectory(cwd.resolve("outline_service"))) {
                return cwd;
            }
            throw new IllegalStateException("outline.python.workspace not found: " + p);
        }
        return p;
    }

    private void drainStream(InputStream stream) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) != -1) {
                log.info("{}", new String(buf, 0, n, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            log.trace("reading stderr", e);
        }
    }
}
