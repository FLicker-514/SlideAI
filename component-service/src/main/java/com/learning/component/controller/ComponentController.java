package com.learning.component.controller;

import com.learning.common.Result;
import com.learning.component.dto.ComponentDetailResponse;
import com.learning.component.dto.ComponentGenerateRequest;
import com.learning.component.dto.ComponentGenerateResponse;
import com.learning.component.dto.ComponentItem;
import com.learning.component.dto.ComponentSaveRequest;
import com.learning.component.service.ComponentLlmService;
import com.learning.component.service.ComponentStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/component")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentLlmService llmService;
    private final ComponentStorageService storageService;

    @PostMapping("/generate")
    public Result<ComponentGenerateResponse> generate(@RequestBody ComponentGenerateRequest request) {
        try {
            return Result.success(llmService.generate(request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/save")
    public Result<ComponentItem> save(@RequestBody ComponentSaveRequest request) {
        try {
            ComponentItem item = storageService.save(request);
            return Result.success(item);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            return Result.error(500, "保存失败: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<List<ComponentItem>> list(@RequestParam("userId") String userId) {
        if (!StringUtils.hasText(userId)) return Result.error(400, "userId 不能为空");
        return Result.success(storageService.list(userId.trim()));
    }

    @GetMapping("/detail")
    public Result<ComponentDetailResponse> detail(@RequestParam("userId") String userId, @RequestParam("id") String id) {
        if (!StringUtils.hasText(userId)) return Result.error(400, "userId 不能为空");
        if (!StringUtils.hasText(id)) return Result.error(400, "id 不能为空");
        return storageService.getDetail(userId.trim(), id.trim())
                .map(Result::success)
                .orElseGet(() -> Result.error(404, "组件不存在"));
    }

    @DeleteMapping("/delete")
    public Result<Boolean> delete(@RequestParam("userId") String userId, @RequestParam("id") String id) {
        if (!StringUtils.hasText(userId)) return Result.error(400, "userId 不能为空");
        if (!StringUtils.hasText(id)) return Result.error(400, "id 不能为空");
        try {
            boolean ok = storageService.delete(userId.trim(), id.trim());
            return Result.success(ok);
        } catch (IOException e) {
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }
}

