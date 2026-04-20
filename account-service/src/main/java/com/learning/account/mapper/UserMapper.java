package com.learning.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learning.account.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
