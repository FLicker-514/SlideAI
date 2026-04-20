package com.learning.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 管理员实体类
 */
@Data
@TableName("Admin")
public class Admin {
    
    @TableId(value = "ID", type = IdType.INPUT)
    private String id;
    
    @TableField("UserName")
    private String userName;
    
    @TableField("Email")
    private String email;
    
    @TableField("Password")
    private String password;
}
