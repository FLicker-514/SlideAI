package com.learning.account.controller;

import com.learning.common.Result;
import com.learning.account.entity.DailyTask;
import com.learning.account.service.DailyTaskService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 每日任务控制器
 * 提供任务管理的REST API接口
 */
@Slf4j
@RestController
@RequestMapping("/account/daily-tasks")
public class DailyTaskController {
    
    @Autowired
    private DailyTaskService dailyTaskService;
    
    /**
     * 获取指定日期的任务列表
     */
    @GetMapping
    public Result<List<TaskResponse>> getTasksByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            List<DailyTask> tasks = dailyTaskService.getTasksByDate(userId, date);
            
            List<TaskResponse> responseList = tasks.stream()
                    .map(task -> {
                        TaskResponse response = new TaskResponse();
                        response.setId(task.getId());
                        response.setTaskContent(task.getTaskContent());
                        response.setIsCompleted(task.getIsCompleted());
                        response.setTaskDate(task.getTaskDate().toString());
                        response.setCreatedAt(task.getCreatedAt().toString());
                        response.setUpdatedAt(task.getUpdatedAt().toString());
                        return response;
                    })
                    .collect(Collectors.toList());
            
            return Result.success(responseList);
        } catch (Exception e) {
            log.error("获取任务列表异常", e);
            return Result.error(500, "获取任务列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建新任务
     */
    @PostMapping
    public Result<TaskResponse> createTask(
            @RequestBody CreateTaskRequest createRequest,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            if (createRequest.getTaskContent() == null || createRequest.getTaskContent().trim().isEmpty()) {
                return Result.error(400, "任务内容不能为空");
            }
            
            LocalDate date = LocalDate.parse(createRequest.getTaskDate());
            DailyTask task = dailyTaskService.createTask(userId, date, createRequest.getTaskContent());
            
            TaskResponse response = new TaskResponse();
            response.setId(task.getId());
            response.setTaskContent(task.getTaskContent());
            response.setIsCompleted(task.getIsCompleted());
            response.setTaskDate(task.getTaskDate().toString());
            response.setCreatedAt(task.getCreatedAt().toString());
            response.setUpdatedAt(task.getUpdatedAt().toString());
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("创建任务异常", e);
            return Result.error(500, "创建任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新任务内容
     */
    @PutMapping("/{taskId}/content")
    public Result<String> updateTaskContent(
            @PathVariable String taskId,
            @RequestBody UpdateTaskContentRequest updateRequest,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            if (updateRequest.getTaskContent() == null || updateRequest.getTaskContent().trim().isEmpty()) {
                return Result.error(400, "任务内容不能为空");
            }
            
            boolean success = dailyTaskService.updateTaskContent(taskId, userId, updateRequest.getTaskContent());
            
            if (success) {
                return Result.success("更新任务成功");
            } else {
                return Result.error(500, "更新任务失败");
            }
        } catch (IllegalArgumentException e) {
            log.warn("更新任务内容参数错误: {}", e.getMessage());
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("更新任务内容异常", e);
            return Result.error(500, "更新任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 切换任务完成状态
     */
    @PutMapping("/{taskId}/toggle")
    public Result<String> toggleTaskStatus(
            @PathVariable String taskId,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            boolean success = dailyTaskService.toggleTaskStatus(taskId, userId);
            
            if (success) {
                return Result.success("切换任务状态成功");
            } else {
                return Result.error(500, "切换任务状态失败");
            }
        } catch (IllegalArgumentException e) {
            log.warn("切换任务状态参数错误: {}", e.getMessage());
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("切换任务状态异常", e);
            return Result.error(500, "切换任务状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除任务
     */
    @DeleteMapping("/{taskId}")
    public Result<String> deleteTask(
            @PathVariable String taskId,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            boolean success = dailyTaskService.deleteTask(taskId, userId);
            
            if (success) {
                return Result.success("删除任务成功");
            } else {
                return Result.error(500, "删除任务失败");
            }
        } catch (IllegalArgumentException e) {
            log.warn("删除任务参数错误: {}", e.getMessage());
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("删除任务异常", e);
            return Result.error(500, "删除任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取任务统计信息
     */
    @GetMapping("/statistics")
    public Result<TaskStatisticsResponse> getTaskStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            DailyTaskService.TaskStatistics statistics = 
                    dailyTaskService.getTaskStatistics(userId, startDate, endDate);
            
            TaskStatisticsResponse response = new TaskStatisticsResponse();
            response.setTotalTasks(statistics.getTotalTasks());
            response.setCompletedTasks(statistics.getCompletedTasks());
            response.setCompletionRate(statistics.getCompletionRate());
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取任务统计异常", e);
            return Result.error(500, "获取任务统计失败: " + e.getMessage());
        }
    }
    
    /**
     * 任务响应DTO
     */
    @Data
    static class TaskResponse {
        private String id;
        private String taskContent;
        private Integer isCompleted;
        private String taskDate;
        private String createdAt;
        private String updatedAt;
    }
    
    /**
     * 创建任务请求DTO
     */
    @Data
    static class CreateTaskRequest {
        private String taskDate;
        private String taskContent;
    }
    
    /**
     * 更新任务内容请求DTO
     */
    @Data
    static class UpdateTaskContentRequest {
        private String taskContent;
    }
    
    /**
     * 任务统计响应DTO
     */
    @Data
    static class TaskStatisticsResponse {
        private long totalTasks;
        private long completedTasks;
        private double completionRate;
    }
}

