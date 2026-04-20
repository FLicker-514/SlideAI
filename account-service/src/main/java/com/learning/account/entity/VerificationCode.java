package com.learning.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验证码实体类
 */
@Data
@TableName("verification_codes")
public class VerificationCode {
    
    @TableId(value = "ID", type = IdType.INPUT)
    private String id;
    
    @TableField("Email")
    private String email;
    
    @TableField("Code")
    private String code;
    
    @TableField("Created_at")
    private LocalDateTime createdAt;
    
    @TableField("Is_used")
    private String isUsed;
}
