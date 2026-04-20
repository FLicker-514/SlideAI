package com.learning.account.controller;

import com.learning.account.entity.Admin;
import com.learning.account.entity.User;
import com.learning.account.service.UserService;
import com.learning.account.util.JwtUtil;
import com.learning.common.Result;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 账号管理控制器
 */
@RestController
@RequestMapping("/")
public class AccountController {
    
    private final UserService userService;
    private final JwtUtil jwtUtil;
    // ✅ 从 application.yml 读取管理员注册密钥
    @Value("${admin.register-secret:MySuperSecretKey}")
    private String adminRegisterSecret;
    
    public AccountController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }
    
    /**
     * 发送验证码
     */
    @PostMapping("/account/send-code")
    public Result<Void> sendVerificationCode(@RequestBody SendCodeRequest request) {
        boolean success = userService.sendVerificationCode(request.getEmail(), request.getPurpose());
        if (success) {
            return Result.success(null);
        } else {
            return Result.error(400, "发送验证码失败");
        }
    }
    
    /**
     * 用户注册
     */
    @PostMapping("/account/register")
    public Result<Void> register(@RequestBody RegisterRequest request) {
        User user = new User();
        user.setUserName(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());
        
        boolean success = userService.register(user, request.getVerificationCode());
        if (success) {
            return Result.success(null);
        } else {
            return Result.error(400, "注册失败，请检查邮箱和验证码");
        }
    }

    /**
     * 登录（支持用户和管理员）
     */
    @PostMapping("/account/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        String loginType = request.getLoginType();
        if (loginType == null || loginType.isEmpty()) {
            loginType = "user";
        }

        if ("admin".equals(loginType)) {
            // --- 管理员登录逻辑 ---
            Admin admin = userService.adminLogin(request.getEmail(), request.getPassword());
            if (admin != null) {
                // ✅✅✅ 修改点：传入 isAdmin = true
                String token = jwtUtil.generateToken(admin.getId(), admin.getUserName(), admin.getEmail(), true);

                LoginResponse response = new LoginResponse();
                response.setToken(token);
                response.setAdmin(admin);
                response.setRole("admin");

                return Result.success(response);
            } else {
                return Result.error(401, "管理员邮箱或密码错误");
            }
        } else {
            // --- 普通用户登录逻辑 ---
            User user = userService.login(request.getEmail(), request.getPassword());
            if (user != null) {
                // ✅✅✅ 修改点：传入 isAdmin = false
                String token = jwtUtil.generateToken(user.getId(), user.getUserName(), user.getEmail(), false);

                LoginResponse response = new LoginResponse();
                response.setToken(token);
                response.setUser(user);
                response.setRole("user");
                
                return Result.success(response);
            } else {
                return Result.error(401, "邮箱或密码错误");
            }
        }
    }

    @GetMapping("/account/info")
    public Result<User> getUserInfo(@RequestHeader("Authorization") String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Result.error(401, "无效的 Token");
        }

        String token = authorization.replace("Bearer ", "");
        String userId = jwtUtil.getUserIdFromToken(token);

        if (userId == null) {
            return Result.error(401, "Token 解析失败");
        }

        User user = userService.getById(userId);

        if (user != null) {
            if (user.getStatus() != null && user.getStatus() == 0) {
                return Result.error(403, "账号已注销");
            }
            user.setPassword(null);
            return Result.success(user);
        } else {
            return Result.error(404, "用户不存在");
        }
    }

    /**
     * 找回密码
     */
    @PostMapping("/account/reset-password")
    public Result<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        boolean success = userService.resetPassword(
                request.getEmail(),
                request.getVerificationCode(),
                request.getNewPassword()
        );
        if (success) {
            return Result.success(null);
        }
        return Result.error(400, "找回密码失败，请检查邮箱与验证码");
    }

    /**
     * 注销账号（需登录）
     */
    @PostMapping("/account/delete")
    public Result<Void> deleteAccount(@RequestHeader("Authorization") String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Result.error(401, "请先登录");
        }
        String token = authorization.replace("Bearer ", "");
        String userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            return Result.error(401, "Token 无效");
        }
        boolean success = userService.deleteAccount(userId);
        if (success) {
            return Result.success(null);
        }
        return Result.error(400, "注销失败");
    }

    /**
     * 更新个人资料（昵称等，需登录）
     */
    @PutMapping("/account/profile")
    public Result<User> updateProfile(
            @RequestHeader("Authorization") String authorization,
            @RequestBody UpdateProfileRequest request
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Result.error(401, "请先登录");
        }
        String token = authorization.replace("Bearer ", "");
        String userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            return Result.error(401, "Token 无效");
        }
        boolean success = userService.updateProfile(userId, request.getUserName());
        if (!success) {
            return Result.error(400, "更新失败");
        }
        User user = userService.getById(userId);
        user.setPassword(null);
        return Result.success(user);
    }
    /**
     * 管理员注册 (通过密钥校验)
     */
    @PostMapping("/account/register-admin")
    public Result<Void> registerAdmin(@RequestBody RegisterRequest request) {
        // 1. 校验密钥 (复用 RegisterRequest 中的 verificationCode 字段作为密钥)
        // 或者你可以专门建一个 AdminRegisterRequest DTO
        String inputSecret = request.getVerificationCode();

        if (inputSecret == null || !inputSecret.equals(adminRegisterSecret)) {
            return Result.error(403, "注册失败：无效的管理员密钥");
        }

        // 2. 构建管理员对象
        Admin admin = new Admin();
        admin.setUserName(request.getUsername());
        admin.setEmail(request.getEmail());
        admin.setPassword(request.getPassword());

        // 3. 调用 Service (无需再传验证码)
        boolean success = userService.registerAdmin(admin);

        if (success) {
            return Result.success(null);
        } else {
            return Result.error(400, "管理员注册失败（可能是邮箱已存在）");
        }
    }
    
    // 请求DTO类
    @Data
    static class SendCodeRequest {
        private String email;
        private String purpose; // "register" 或 "reset"
    }
    
    @Data
    static class RegisterRequest {
        private String username;
        private String email;
        private String password;
        private String verificationCode;
    }
    
    @Data
    static class LoginRequest {
        private String email;
        private String password;
        private String loginType; // "user" 或 "admin"
    }
    
    @Data
    static class LoginResponse {
        private String token;
        private User user;
        private Admin admin;
        private String role; // "user" 或 "admin"
    }

    @Data
    static class ResetPasswordRequest {
        private String email;
        private String verificationCode;
        private String newPassword;
    }

    @Data
    static class UpdateProfileRequest {
        private String userName;
    }
}
