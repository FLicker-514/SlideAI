package com.learning.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user")
public class User {

    @TableId(value = "ID", type = IdType.INPUT)
    private String id;

    @TableField("UserName")
    private String userName;

    @TableField("Email")
    private String email;

    @TableField("Password")
    private String password;

    @TableField("VipLevel")
    private Integer vipLevel; // 0:普通, 1:VIP1, 2:VIP2

    // ✅ 新增：状态字段
    @TableField("Status")
    private Integer status; // 1:正常, 0:封禁
}
