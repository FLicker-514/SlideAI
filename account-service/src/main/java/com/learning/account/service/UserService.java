package com.learning.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learning.account.entity.Admin;
import com.learning.account.entity.User;
import com.learning.account.entity.VerificationCode;
import com.learning.account.mapper.AdminMapper;
import com.learning.account.mapper.UserMapper;
import com.learning.account.mapper.VerificationCodeMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

/**
 * 用户服务
 */
@Service
public class UserService {
    
    private final UserMapper userMapper;
    private final AdminMapper adminMapper;
    private final VerificationCodeMapper verificationCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final UserProfileService userProfileService;
    
    public UserService(UserMapper userMapper,
                      AdminMapper adminMapper,
                      VerificationCodeMapper verificationCodeMapper,
                      PasswordEncoder passwordEncoder,
                      EmailService emailService,
                      UserProfileService userProfileService) {
        this.userMapper = userMapper;
        this.adminMapper = adminMapper;
        this.verificationCodeMapper = verificationCodeMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.userProfileService = userProfileService;
    }
    @Transactional
    public boolean registerAdmin(Admin admin) {
        try {
            // 1. 检查邮箱是否已存在 (查 Admin 表)
            LambdaQueryWrapper<Admin> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Admin::getEmail, admin.getEmail());
            long count = adminMapper.selectCount(queryWrapper);

            if (count > 0) {
                System.out.println("管理员邮箱已存在，注册失败");
                return false;
            }

            // 2. 准备数据
            admin.setId(UUID.randomUUID().toString());
            admin.setPassword(passwordEncoder.encode(admin.getPassword()));

            // 3. 插入数据库
            int rowsAffected = adminMapper.insert(admin);

            if (rowsAffected > 0) {
                System.out.println("管理员注册成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            System.err.println("管理员注册时发生错误：" + e.getMessage());
            return false;
        }
    }
    
    /**
     * 发送验证码
     */
    @Transactional
    public boolean sendVerificationCode(String email, String purpose) {
        try {
            // 检查邮箱是否已存在
            LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(User::getEmail, email);
            long count = userMapper.selectCount(queryWrapper);
            
            // 判断用途
            if ("register".equals(purpose) && count > 0) {
                System.out.println("邮箱已注册，注册失败");
                return false; // 注册时邮箱不能已存在
            }
            
            if ("reset".equals(purpose) && count == 0) {
                System.out.println("邮箱未注册，找回密码失败");
                return false; // 找回密码时邮箱必须已存在
            }
            
            System.out.println("准备发送验证码");
            
            // 生成验证码
            String verificationCode = generateVerificationCode();
            System.out.println("生成的验证码为：" + verificationCode);
            
            // 发送验证码邮件
            boolean mailSent = emailService.sendVerificationCode(email, verificationCode);
            if (!mailSent) {
                System.out.println("验证码发送失败");
                return false;
            } else {
                System.out.println("验证码发送成功");
            }
            
            // 存入Verification_Codes表
            VerificationCode code = new VerificationCode();
            code.setId(UUID.randomUUID().toString());
            code.setEmail(email);
            code.setCode(verificationCode);
            code.setCreatedAt(LocalDateTime.now());
            code.setIsUsed("0");  // 字符串类型：0=未使用，1=已使用
            verificationCodeMapper.insert(code);
            
            System.out.println("验证码已写入数据库");
            return true;
        } catch (Exception e) {
            System.err.println("发送验证码时发生错误：" + e.getMessage());
            return false;
        }
    }
    
    /**
     * 验证验证码
     */
    @Transactional
    public boolean verifyEmailCode(String email, String code) {
        try {
            // 去除前后空格
            email = email != null ? email.trim() : "";
            code = code != null ? code.trim() : "";
            
            System.out.println("开始验证验证码 - 邮箱: " + email + ", 验证码: " + code);
            
            // 查询最近10分钟内的验证码
            LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
            System.out.println("查询时间范围: " + tenMinutesAgo + " 到现在");
            
            // 先查询该邮箱的所有未使用验证码（用于调试，兼容字符串和数字格式）
            LambdaQueryWrapper<VerificationCode> debugWrapper = new LambdaQueryWrapper<>();
            debugWrapper.eq(VerificationCode::getEmail, email)
                       .and(wrapper -> wrapper.eq(VerificationCode::getIsUsed, "0")
                                             .or()
                                             .eq(VerificationCode::getIsUsed, 0)
                                             .or()
                                             .isNull(VerificationCode::getIsUsed))
                       .orderByDesc(VerificationCode::getCreatedAt);
            long count = verificationCodeMapper.selectCount(debugWrapper);
            System.out.println("该邮箱未使用的验证码数量: " + count);
            
            // 正式查询匹配的验证码（使用原生SQL避免字段映射问题）
            VerificationCode verificationCode = verificationCodeMapper.findValidCode(email, code);
            
            // 备用方案：使用Lambda查询（兼容字符串和数字格式）
            if (verificationCode == null) {
                LambdaQueryWrapper<VerificationCode> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(VerificationCode::getEmail, email)
                           .eq(VerificationCode::getCode, code)
                           .and(wrapper -> wrapper.eq(VerificationCode::getIsUsed, "0")
                                                 .or()
                                                 .eq(VerificationCode::getIsUsed, 0)
                                                 .or()
                                                 .isNull(VerificationCode::getIsUsed))
                           .ge(VerificationCode::getCreatedAt, tenMinutesAgo)
                           .orderByDesc(VerificationCode::getCreatedAt)
                           .last("LIMIT 1");
                
                verificationCode = verificationCodeMapper.selectOne(queryWrapper);
            }
            
            if (verificationCode != null) {
                System.out.println("找到匹配的验证码 - ID: " + verificationCode.getId() 
                    + ", 创建时间: " + verificationCode.getCreatedAt()
                    + ", Is_used: " + verificationCode.getIsUsed());
                
                // 标记为已使用
                int updated = verificationCodeMapper.markAsUsed(email, code);
                System.out.println("更新行数: " + updated);
                System.out.println("验证码验证成功");
                return true;
            } else {
                System.out.println("未找到匹配的验证码");
                // 进一步调试：检查是否是时间过期或已被使用
                LambdaQueryWrapper<VerificationCode> allWrapper = new LambdaQueryWrapper<>();
                allWrapper.eq(VerificationCode::getEmail, email)
                          .eq(VerificationCode::getCode, code)
                          .orderByDesc(VerificationCode::getCreatedAt)
                          .last("LIMIT 1");
                VerificationCode latest = verificationCodeMapper.selectOne(allWrapper);
                if (latest != null) {
                    System.out.println("找到相同验证码但条件不匹配 - 创建时间: " + latest.getCreatedAt()
                        + ", Is_used: " + latest.getIsUsed()
                        + ", 是否过期: " + (latest.getCreatedAt().isBefore(tenMinutesAgo)));
                } else {
                    System.out.println("数据库中不存在该验证码");
                }
            }
            
            return false;
        } catch (Exception e) {
            System.err.println("验证码验证错误：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 用户注册
     */
    @Transactional
    public boolean register(User user, String verificationCode) {
        try {
            // 先验证验证码
            if (!verifyEmailCode(user.getEmail(), verificationCode)) {
                System.out.println("验证码验证失败");
                return false;
            }
            
            // 检查邮箱是否已存在
            LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(User::getEmail, user.getEmail());
            long count = userMapper.selectCount(queryWrapper);
            
            if (count > 0) {
                System.out.println("邮箱已经存在，注册失败");
                return false;
            }
            
            System.out.println("邮箱不存在");
            
            // 设置用户信息
            String userId = UUID.randomUUID().toString();
            user.setId(userId);
            user.setPassword(passwordEncoder.encode(user.getPassword()));
            // 如果数据库表有createTime字段，取消下面这行的注释
            // user.setCreateTime(LocalDateTime.now());
            
            // 插入用户
            int rowsAffected = userMapper.insert(user);
            
            if (rowsAffected > 0) {
                System.out.println("插入成功");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            System.err.println("注册时发生未知错误：" + e.getMessage());
            return false;
        }
    }
    
    /**
     * 用户登录
     */
    public User login(String email, String password) {
        try {
            // 查询用户
            LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(User::getEmail, email);
            User user = userMapper.selectOne(queryWrapper);
            
            if (user == null) {
                System.out.println("用户不存在");
                return null;
            }
            if (user.getStatus() != null && user.getStatus() == 0) {
                System.out.println("账号已注销");
                return null;
            }
            System.out.println("用户存在");
            // 验证密码
            if (passwordEncoder.matches(password, user.getPassword())) {
                System.out.println("密码验证成功");
                // 清除密码字段，不返回给前端
                user.setPassword(null);
                return user;
            } else {
                System.out.println("密码验证失败");
                return null;
            }
        } catch (Exception e) {
            System.err.println("登录时发生错误：" + e.getMessage());
            return null;
        }
    }
    
    /**
     * 管理员登录
     */
    public Admin adminLogin(String email, String password) {
        try {
            // 查询管理员
            LambdaQueryWrapper<Admin> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Admin::getEmail, email);
            Admin admin = adminMapper.selectOne(queryWrapper);
            
            if (admin == null) {
                System.out.println("管理员不存在");
                return null;
            }
            
            System.out.println("管理员存在");
            
            // 验证密码
            if (passwordEncoder.matches(password, admin.getPassword())) {
                System.out.println("管理员密码验证成功");
                // 清除密码字段，不返回给前端
                admin.setPassword(null);
                return admin;
            } else {
                System.out.println("管理员密码验证失败");
                return null;
            }
        } catch (Exception e) {
            System.err.println("管理员登录时发生错误：" + e.getMessage());
            return null;
        }
    }
    
    /**
     * 生成6位验证码
     */
    private String generateVerificationCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }
    // ✅ 新增：根据ID获取用户
    public User getById(String userId) {
        return userMapper.selectById(userId);
    }

    // ✅ 新增：更新用户状态 (封禁/解封)
    public void updateStatus(String userId, Integer status) {
        User user = new User();
        user.setId(userId);
        user.setStatus(status);
        userMapper.updateById(user);
    }

    // ✅ 新增：更新用户VIP等级
    public void updateVip(String userId, Integer vipLevel) {
        User user = new User();
        user.setId(userId);
        user.setVipLevel(vipLevel);
        userMapper.updateById(user);
    }

    /**
     * 找回密码：验证验证码后重置密码
     */
    @Transactional
    public boolean resetPassword(String email, String verificationCode, String newPassword) {
        if (!verifyEmailCode(email, verificationCode)) {
            return false;
        }
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.eq(User::getEmail, email);
        User user = userMapper.selectOne(q);
        if (user == null) {
            return false;
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        return userMapper.updateById(user) > 0;
    }

    /**
     * 注销账号：逻辑删除或禁用（此处采用将 status 置为 0 表示已注销，可改为物理删除）
     */
    @Transactional
    public boolean deleteAccount(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        user.setStatus(0); // 0 表示已注销/禁用
        return userMapper.updateById(user) > 0;
    }

    /**
     * 更新个人资料（用户名等）
     */
    @Transactional
    public boolean updateProfile(String userId, String userName) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        if (userName != null && !userName.trim().isEmpty()) {
            user.setUserName(userName.trim());
        }
        return userMapper.updateById(user) > 0;
    }

    // ✅ 新增：分页查询用户列表 (供Admin后台使用)
    public IPage<User> getUserPage(int page, int size) {
        Page<User> pageParam = new Page<>(page, size);
        // 这里的 null 表示没有查询条件，查询所有
        return userMapper.selectPage(pageParam, null);
    }
    
    /**
     * 检查并更新用户画像（异步调用，不阻塞）
     * @param userId 用户ID
     */
    @org.springframework.scheduling.annotation.Async
    public void checkAndUpdateUserProfile(String userId) {
        try {
            userProfileService.checkAndUpdateProfile(userId);
        } catch (Exception e) {
            // 用户画像更新失败不影响其他功能，只记录日志
            // 日志已在UserProfileService中记录
        }
    }
    
}
