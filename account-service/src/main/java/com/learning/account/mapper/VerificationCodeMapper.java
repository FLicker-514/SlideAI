package com.learning.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learning.account.entity.VerificationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface VerificationCodeMapper extends BaseMapper<VerificationCode> {
    
    @Update("UPDATE verification_codes SET Is_used = '1' WHERE Email = #{email} AND Code = #{code} AND (Is_used = '0' OR Is_used = 0)")
    int markAsUsed(@Param("email") String email, @Param("code") String code);
    
    /**
     * 查询验证码（使用原生SQL，避免字段映射问题）
     * 兼容字符串 '0' 和数字 0 两种格式
     */
    @Select("SELECT ID, Email, Code, Created_at, Is_used FROM verification_codes " +
            "WHERE Email = #{email} AND Code = #{code} " +
            "AND (Is_used = '0' OR Is_used = 0 OR Is_used IS NULL) " +
            "AND Created_at >= DATE_SUB(NOW(), INTERVAL 10 MINUTE) " +
            "ORDER BY Created_at DESC LIMIT 1")
    VerificationCode findValidCode(@Param("email") String email, @Param("code") String code);
}
