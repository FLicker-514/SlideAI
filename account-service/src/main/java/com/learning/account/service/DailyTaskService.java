package com.learning.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learning.account.entity.DailyTask;
import com.learning.account.mapper.DailyTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 每日任务服务类
 * 提供任务的增删改查功能
 */
@Slf4j
@Service
public class DailyTaskService {
    
    @Autowired
    private DailyTaskMapper dailyTaskMapper;
    
    /**
     * 获取指定日期的任务列表
     * 
     * @param userId 用户ID
     * @param date 日期
     * @return 任务列表
     */
    public List<DailyTask> getTasksByDate(String userId, LocalDate date) {
        try {
            LambdaQueryWrapper<DailyTask> query = new LambdaQueryWrapper<>();
            query.eq(DailyTask::getUserId, userId)
                    .eq(DailyTask::getTaskDate, date)
                    .orderByAsc(DailyTask::getCreatedAt);
            
            return dailyTaskMapper.selectList(query);
        } catch (Exception e) {
            log.error("获取任务列表异常: userId={}, date={}", userId, date, e);
            throw new RuntimeException("获取任务列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建新任务
     * 
     * @param userId 用户ID
     * @param date 日期
     * @param content 任务内容
     * @return 创建的任务对象
     */
    public DailyTask createTask(String userId, LocalDate date, String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalArgumentException("任务内容不能为空");
            }
            
            DailyTask task = new DailyTask();
            task.setUserId(userId);
            task.setTaskDate(date);
            task.setTaskContent(content.trim());
            task.setIsCompleted(0);
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            
            dailyTaskMapper.insert(task);
            log.info("创建任务成功: userId={}, date={}, taskId={}", userId, date, task.getId());
            
            return task;
        } catch (Exception e) {
            log.error("创建任务异常: userId={}, date={}, content={}", userId, date, content, e);
            throw new RuntimeException("创建任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新任务内容
     * 
     * @param taskId 任务ID
     * @param userId 用户ID（用于验证权限）
     * @param content 新的任务内容
     * @return 是否更新成功
     */
    public boolean updateTaskContent(String taskId, String userId, String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalArgumentException("任务内容不能为空");
            }
            
            DailyTask task = dailyTaskMapper.selectById(taskId);
            if (task == null) {
                throw new IllegalArgumentException("任务不存在");
            }
            
            if (!task.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权限修改此任务");
            }
            
            task.setTaskContent(content.trim());
            task.setUpdatedAt(LocalDateTime.now());
            
            int result = dailyTaskMapper.updateById(task);
            log.info("更新任务内容成功: taskId={}, userId={}", taskId, userId);
            
            return result > 0;
        } catch (Exception e) {
            log.error("更新任务内容异常: taskId={}, userId={}", taskId, userId, e);
            throw new RuntimeException("更新任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 切换任务完成状态
     * 
     * @param taskId 任务ID
     * @param userId 用户ID（用于验证权限）
     * @return 是否更新成功
     */
    public boolean toggleTaskStatus(String taskId, String userId) {
        try {
            DailyTask task = dailyTaskMapper.selectById(taskId);
            if (task == null) {
                throw new IllegalArgumentException("任务不存在");
            }
            
            if (!task.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权限修改此任务");
            }
            
            // 切换完成状态
            task.setIsCompleted(task.getIsCompleted() == 1 ? 0 : 1);
            task.setUpdatedAt(LocalDateTime.now());
            
            int result = dailyTaskMapper.updateById(task);
            log.info("切换任务状态成功: taskId={}, userId={}, newStatus={}", 
                    taskId, userId, task.getIsCompleted());
            
            return result > 0;
        } catch (Exception e) {
            log.error("切换任务状态异常: taskId={}, userId={}", taskId, userId, e);
            throw new RuntimeException("切换任务状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除任务
     * 
     * @param taskId 任务ID
     * @param userId 用户ID（用于验证权限）
     * @return 是否删除成功
     */
    public boolean deleteTask(String taskId, String userId) {
        try {
            DailyTask task = dailyTaskMapper.selectById(taskId);
            if (task == null) {
                throw new IllegalArgumentException("任务不存在");
            }
            
            if (!task.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权限删除此任务");
            }
            
            int result = dailyTaskMapper.deleteById(taskId);
            log.info("删除任务成功: taskId={}, userId={}", taskId, userId);
            
            return result > 0;
        } catch (Exception e) {
            log.error("删除任务异常: taskId={}, userId={}", taskId, userId, e);
            throw new RuntimeException("删除任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定日期范围内的任务统计
     * 
     * @param userId 用户ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 任务统计信息
     */
    public TaskStatistics getTaskStatistics(String userId, LocalDate startDate, LocalDate endDate) {
        try {
            LambdaQueryWrapper<DailyTask> query = new LambdaQueryWrapper<>();
            query.eq(DailyTask::getUserId, userId)
                    .ge(DailyTask::getTaskDate, startDate)
                    .le(DailyTask::getTaskDate, endDate);
            
            List<DailyTask> tasks = dailyTaskMapper.selectList(query);
            
            long totalTasks = tasks.size();
            long completedTasks = tasks.stream()
                    .filter(task -> task.getIsCompleted() == 1)
                    .count();
            
            TaskStatistics statistics = new TaskStatistics();
            statistics.setTotalTasks(totalTasks);
            statistics.setCompletedTasks(completedTasks);
            statistics.setCompletionRate(totalTasks > 0 ? 
                    (double) completedTasks / totalTasks * 100 : 0.0);
            
            return statistics;
        } catch (Exception e) {
            log.error("获取任务统计异常: userId={}, startDate={}, endDate={}", 
                    userId, startDate, endDate, e);
            throw new RuntimeException("获取任务统计失败: " + e.getMessage());
        }
    }
    
    /**
     * 任务统计信息类
     */
    public static class TaskStatistics {
        private long totalTasks;
        private long completedTasks;
        private double completionRate;
        
        public long getTotalTasks() {
            return totalTasks;
        }
        
        public void setTotalTasks(long totalTasks) {
            this.totalTasks = totalTasks;
        }
        
        public long getCompletedTasks() {
            return completedTasks;
        }
        
        public void setCompletedTasks(long completedTasks) {
            this.completedTasks = completedTasks;
        }
        
        public double getCompletionRate() {
            return completionRate;
        }
        
        public void setCompletionRate(double completionRate) {
            this.completionRate = completionRate;
        }
    }
}

