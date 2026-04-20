package com.learning.account.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.learning.account.entity.User;
import com.learning.account.service.UserService;
import com.learning.account.service.VipUpgradeService;
import com.learning.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 内部调用控制器
 * 供 Admin Service / LLM Service 等微服务通过 Feign 调用
 * 不对外部网关开放 (Gateway 应配置路由忽略 /internal/**)
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;
    private final VipUpgradeService vipUpgradeService;
    private final com.learning.account.service.UserProfileService userProfileService;

    /**
     * 根据ID获取用户详情 (供 Admin 和 LLM Service 鉴权使用)
     */
    @GetMapping("/{userId}")
    public Result<Map<String, Object>> getUserById(@PathVariable("userId") String userId) {
        User user = userService.getById(userId);
        if (user != null) {
            user.setPassword(null);
            
            // 计算用户的Token消耗量
            Long tokenUsage = vipUpgradeService.getTotalTokenUsage(userId);
            
            // 封装返回数据
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", user.getId());
            userData.put("userName", user.getUserName());
            userData.put("email", user.getEmail());
            userData.put("vipLevel", user.getVipLevel());
            userData.put("status", user.getStatus());
            userData.put("tokenUsage", tokenUsage);
            
            return Result.success(userData);
        }
        return Result.error(404, "用户不存在");
    }

    /**
     * 分页获取用户列表 (供 Admin 管理后台使用)
     */
    @GetMapping
    public Result<Map<String, Object>> getUserList(@RequestParam("page") int page, @RequestParam("size") int size) {
        IPage<User> userPage = userService.getUserPage(page, size);

        // 为每个用户计算Token消耗量
        userPage.getRecords().forEach(user -> {
            Long tokenUsage = vipUpgradeService.getTotalTokenUsage(user.getId());
            // 将Token消耗量添加到用户对象中（通过反射或扩展DTO）
            // 由于User实体没有tokenUsage字段，我们需要在返回时单独处理
        });

        // 封装成分页数据结构，并添加Token消耗量
        Map<String, Object> data = new HashMap<>();
        // 将User对象转换为Map，添加tokenUsage字段
        java.util.List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (User user : userPage.getRecords()) {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("id", user.getId());
            userMap.put("userName", user.getUserName());
            userMap.put("email", user.getEmail());
            userMap.put("vipLevel", user.getVipLevel());
            userMap.put("status", user.getStatus());
            userMap.put("tokenUsage", vipUpgradeService.getTotalTokenUsage(user.getId()));
            records.add(userMap);
        }
        data.put("records", records);
        data.put("total", userPage.getTotal());
        data.put("current", userPage.getCurrent());
        data.put("size", userPage.getSize());

        return Result.success(data);
    }

    /**
     * 修改用户状态 (封禁/解封)
     */
    @PutMapping("/{userId}/status")
    public Result<Void> updateUserStatus(@PathVariable("userId") String userId, @RequestParam("status") Integer status) {
        userService.updateStatus(userId, status);
        return Result.success(null);
    }

    /**
     * 修改用户 VIP 等级
     */
    @PutMapping("/{userId}/vip")
    public Result<Void> updateUserVip(@PathVariable("userId") String userId, @RequestParam("vipLevel") Integer vipLevel) {
        userService.updateVip(userId, vipLevel);
        return Result.success(null);
    }

    /**
     * 检查并更新用户的VIP等级（内部接口，供其他服务调用）
     * 根据Token消耗量自动升级
     */
    @PostMapping("/{userId}/check-vip-upgrade")
    public Result<Boolean> checkVipUpgrade(@PathVariable("userId") String userId) {
        boolean upgraded = vipUpgradeService.checkAndUpgradeVip(userId);
        return Result.success(upgraded);
    }
    
    /**
     * 获取用户画像（内部接口）
     * @param userId 用户ID
     * @return 用户画像信息
     */
    @GetMapping("/{userId}/profile")
    public Map<String, Object> getUserProfile(@PathVariable("userId") String userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String profileContent = userProfileService.getUserProfile(userId);
            result.put("code", 200);
            result.put("message", "success");
            result.put("profileContent", profileContent != null ? profileContent : "");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "获取用户画像失败: " + e.getMessage());
            result.put("profileContent", "");
        }
        return result;
    }
}