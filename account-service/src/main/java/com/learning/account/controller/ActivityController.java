package com.learning.account.controller;

import com.learning.common.Result;
import com.learning.account.service.ActivityService;
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
 * 活跃度控制器
 * 提供用户活跃度统计和热力图数据接口
 */
@Slf4j
@RestController
@RequestMapping("/account/activity")
public class ActivityController {
    
    @Autowired
    private ActivityService activityService;
    
    /**
     * 获取统计数据
     */
    @GetMapping("/statistics")
    public Result<StatisticsResponse> getStatistics(HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            ActivityService.StatisticsResult statistics = activityService.getStatistics(userId);
            
            StatisticsResponse response = new StatisticsResponse();
            response.setMonthStudyDays(statistics.getMonthActiveDays());
            response.setTotalStudyDays(statistics.getTotalActiveDays());
            response.setConsecutiveDays(statistics.getConsecutiveDays());
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取统计数据异常", e);
            return Result.error(500, "获取统计数据异常: " + e.getMessage());
        }
    }
    
    /**
     * 获取日历数据（用于热力图展示）
     */
    @GetMapping("/calendar")
    public Result<List<CalendarResponse>> getActivityCalendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                return Result.error(401, "未登录或无法获取用户信息");
            }
            
            List<ActivityService.CalendarData> calendarDataList = 
                    activityService.getActivityCalendar(userId, startDate, endDate);
            
            // 转换为响应DTO
            List<CalendarResponse> responseList = calendarDataList.stream()
                    .map(data -> {
                        CalendarResponse response = new CalendarResponse();
                        response.setDate(data.getDate().toString());
                        response.setCount(data.getCount());
                        return response;
                    })
                    .collect(Collectors.toList());
            
            return Result.success(responseList);
        } catch (Exception e) {
            log.error("获取日历数据异常", e);
            return Result.error(500, "获取日历数据异常: " + e.getMessage());
        }
    }
    
    /**
     * 统计数据响应DTO
     */
    @Data
    static class StatisticsResponse {
        private long monthStudyDays;      // 本月活跃天数
        private long totalStudyDays;      // 累计活跃天数
        private long consecutiveDays;    // 连续活跃天数
    }
    
    /**
     * 日历数据响应DTO
     */
    @Data
    static class CalendarResponse {
        private String date;    // 日期（YYYY-MM-DD格式）
        private int count;      // 该日的活动强度
    }
}


