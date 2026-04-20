package com.learning.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learning.account.entity.UserTokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Token使用记录Mapper
 */
@Mapper
public interface UserTokenUsageMapper extends BaseMapper<UserTokenUsage> {
    
    /**
     * 计算用户的总Token消耗量（作为经验值）
     * @param userId 用户ID
     * @return Token总消耗量，如果用户没有记录则返回0
     */
    @Select("SELECT COALESCE(SUM(TokensUsed), 0) FROM user_token_usage WHERE UserId = #{userId}")
    Long sumTokensByUserId(@Param("userId") String userId);
}


