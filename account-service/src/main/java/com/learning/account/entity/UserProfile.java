package com.learning.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 用户画像实体类
 * 用于存储AI生成的用户学习画像
 */
@Data
@TableName("user_profile")
public class UserProfile {
    
    /**
     * 用户ID（主键）
     */
    @TableId(value = "UserId", type = IdType.INPUT)
    private String userId;
    
    /**
     * 用户画像内容（AI生成的一段话）
     */
    @TableField("ProfileContent")
    private String profileContent;
    
    /**
     * 最后更新时间
     */
    @TableField("UpdatedAt")
    private LocalDate updatedAt;
    
    /**
     * 创建时间
     */
    @TableField("CreatedAt")
    private LocalDate createdAt;
}

