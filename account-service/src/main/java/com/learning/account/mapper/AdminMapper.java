package com.learning.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learning.account.entity.Admin;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
}
