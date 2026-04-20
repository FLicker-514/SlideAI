package com.learning.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Token使用记录实体类
 * 用于统计用户活跃度
 */
@Data
@TableName("user_token_usage")
public class UserTokenUsage {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    @TableField("ID")
    private String id;

    @TableField("UserId")
    private String userId;

    @TableField("Source")
    private Integer source; // 1=知识问答, 2=信息分析, 3=其他

    @TableField("Model")
    private String model;

    @TableField("TokensUsed")
    private Integer tokensUsed;

    @TableField("Date")
    private LocalDateTime date;
}


