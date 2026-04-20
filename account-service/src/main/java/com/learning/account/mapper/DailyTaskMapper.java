package com.learning.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learning.account.entity.DailyTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 每日任务Mapper接口
 */
@Mapper
public interface DailyTaskMapper extends BaseMapper<DailyTask> {
}

