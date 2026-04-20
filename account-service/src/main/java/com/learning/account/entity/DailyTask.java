package com.learning.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日任务实体类
 * 用于记录用户的每日学习任务备忘录
 */
@Data
@TableName("daily_task")
public class DailyTask {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    @TableField("ID")
    private String id;

    @TableField("UserId")
    private String userId;

    @TableField("TaskDate")
    private LocalDate taskDate;

    @TableField("TaskContent")
    private String taskContent;

    @TableField("IsCompleted")
    private Integer isCompleted; // 0:未完成, 1:已完成

    @TableField("CreatedAt")
    private LocalDateTime createdAt;

    @TableField("UpdatedAt")
    private LocalDateTime updatedAt;
}

