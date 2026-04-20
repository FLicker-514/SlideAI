package com.learning.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learning.account.entity.UserTokenUsage;
import com.learning.account.mapper.UserTokenUsageMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 活跃度服务类
 * 基于Token使用量计算用户活跃度
 */
@Slf4j
@Service
public class ActivityService {
    
    @Autowired
    private UserTokenUsageMapper tokenUsageMapper;
    
    /**
     * 获取统计数据
     * 
     * @param userId 用户ID
     * @return 统计数据对象
     */
    public StatisticsResult getStatistics(String userId) {
        try {
            if (userId == null || userId.isEmpty()) {
                log.warn("用户ID不能为空");
                return new StatisticsResult(0, 0, 0);
            }
            
            // 获取所有活跃日期（基于token使用）
            Set<LocalDate> activeDates = getAllActiveDates(userId);
            
            // 获取当前日期
            LocalDate today = LocalDate.now();
            LocalDate firstDayOfMonth = today.withDayOfMonth(1);
            
            // 1. 本月活跃天数
            long monthActiveDays = activeDates.stream()
                    .filter(date -> !date.isBefore(firstDayOfMonth) && !date.isAfter(today))
                    .count();
            
            // 2. 累计活跃天数
            long totalActiveDays = activeDates.size();
            
            // 3. 连续活跃天数
            long consecutiveDays = calculateConsecutiveDays(activeDates, today);
            
            return new StatisticsResult(monthActiveDays, totalActiveDays, consecutiveDays);
        } catch (Exception e) {
            log.error("获取统计数据异常", e);
            return new StatisticsResult(0, 0, 0);
        }
    }
    
    /**
     * 获取日历数据（用于热力图展示）
     * 按日期聚合统计活动强度（基于token使用量）
     * 
     * @param userId 用户ID
     * @param startDate 开始日期（如果为null，默认查询最近一年）
     * @param endDate 结束日期（如果为null，默认为今天）
     * @return 日历数据列表，每个元素包含日期和该日的活动强度
     */
    public List<CalendarData> getActivityCalendar(String userId, LocalDate startDate, LocalDate endDate) {
        try {
            if (userId == null || userId.isEmpty()) {
                log.warn("用户ID不能为空");
                return new ArrayList<>();
            }
            
            // 设置默认日期范围（最近一年）
            if (endDate == null) {
                endDate = LocalDate.now();
            }
            if (startDate == null) {
                startDate = endDate.minusYears(1);
            }
            
            // 查询指定日期范围内的token使用记录
            LambdaQueryWrapper<UserTokenUsage> tokenQuery = new LambdaQueryWrapper<>();
            tokenQuery.eq(UserTokenUsage::getUserId, userId)
                    .ge(UserTokenUsage::getDate, startDate.atStartOfDay())
                    .le(UserTokenUsage::getDate, endDate.atTime(23, 59, 59))
                    .orderByAsc(UserTokenUsage::getDate);
            
            List<UserTokenUsage> tokenUsages = tokenUsageMapper.selectList(tokenQuery);
            
            // 按日期聚合统计活动强度
            Map<LocalDate, Integer> dateIntensityMap = new HashMap<>();
            
            // 统计token使用量（每1000个token算1个活动强度）
            for (UserTokenUsage usage : tokenUsages) {
                LocalDate date = usage.getDate().toLocalDate();
                int intensity = (usage.getTokensUsed() != null ? usage.getTokensUsed() : 0) / 1000;
                if (intensity < 1 && usage.getTokensUsed() != null && usage.getTokensUsed() > 0) {
                    intensity = 1; // 至少算1个活动强度
                }
                dateIntensityMap.merge(date, intensity, Integer::sum);
            }
            
            // 转换为CalendarData列表
            List<CalendarData> calendarDataList = new ArrayList<>();
            for (Map.Entry<LocalDate, Integer> entry : dateIntensityMap.entrySet()) {
                CalendarData data = new CalendarData();
                data.setDate(entry.getKey());
                data.setCount(entry.getValue());
                calendarDataList.add(data);
            }
            
            // 按日期排序
            calendarDataList.sort(Comparator.comparing(CalendarData::getDate));
            
            log.info("获取日历数据成功: userId={}, 日期范围={} 到 {}, 共{}条记录", 
                    userId, startDate, endDate, calendarDataList.size());
            
            return calendarDataList;
        } catch (Exception e) {
            log.error("获取日历数据异常", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取所有活跃日期（基于token使用）
     */
    private Set<LocalDate> getAllActiveDates(String userId) {
        Set<LocalDate> activeDates = new HashSet<>();
        
        // 获取所有token使用日期
        LambdaQueryWrapper<UserTokenUsage> tokenQuery = new LambdaQueryWrapper<>();
        tokenQuery.eq(UserTokenUsage::getUserId, userId);
        List<UserTokenUsage> tokenUsages = tokenUsageMapper.selectList(tokenQuery);
        activeDates.addAll(tokenUsages.stream()
                .map(usage -> usage.getDate().toLocalDate())
                .collect(Collectors.toSet()));
        
        return activeDates;
    }
    
    /**
     * 计算连续活跃天数
     * 从今天往前数，连续有活动记录的天数
     */
    private long calculateConsecutiveDays(Set<LocalDate> activeDates, LocalDate today) {
        if (activeDates == null || activeDates.isEmpty()) {
            return 0;
        }
        
        // 转换为排序列表（降序）
        List<LocalDate> sortedDates = activeDates.stream()
                .sorted((a, b) -> b.compareTo(a))
                .collect(Collectors.toList());
        
        // 从今天开始往前数连续天数
        long consecutiveDays = 0;
        LocalDate currentDate = today;
        
        for (LocalDate activeDate : sortedDates) {
            // 如果活跃日期等于当前检查的日期，连续天数+1
            if (activeDate.equals(currentDate)) {
                consecutiveDays++;
                currentDate = currentDate.minusDays(1);
            } else if (activeDate.isBefore(currentDate)) {
                // 如果活跃日期早于当前检查的日期，说明中间有断档，停止计数
                break;
            }
            // 如果活跃日期晚于当前检查的日期，继续查找
        }
        
        return consecutiveDays;
    }
    
    /**
     * 日历数据类
     * 用于热力图展示
     */
    @Data
    public static class CalendarData {
        private LocalDate date;    // 日期
        private int count;         // 该日的活动强度
    }
    
    /**
     * 统计数据结果类
     */
    @Data
    public static class StatisticsResult {
        private long monthActiveDays;      // 本月活跃天数
        private long totalActiveDays;      // 累计活跃天数
        private long consecutiveDays;      // 连续活跃天数
        
        public StatisticsResult(long monthActiveDays, long totalActiveDays, long consecutiveDays) {
            this.monthActiveDays = monthActiveDays;
            this.totalActiveDays = totalActiveDays;
            this.consecutiveDays = consecutiveDays;
        }
    }
}
