package com.learning.account.service;

import com.learning.account.entity.User;
import com.learning.account.mapper.UserMapper;
import com.learning.account.mapper.UserTokenUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VIP等级升级服务
 * 根据用户Token消耗量（经验值）自动计算和更新VIP等级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipUpgradeService {

    private final UserTokenUsageMapper userTokenUsageMapper;
    private final UserMapper userMapper;

    /**
     * 计算用户的总Token消耗量（经验值）
     * 
     * @param userId 用户ID
     * @return Token总消耗量
     */
    public Long getTotalTokenUsage(String userId) {
        Long total = userTokenUsageMapper.sumTokensByUserId(userId);
        return total != null ? total : 0L;
    }

    /**
     * 根据Token消耗量计算应该的VIP等级
     * 升级规则：以 log1000 取整作为等级
     * 公式：VIP等级 = floor(log1000(tokenUsage)) = floor(log10(tokenUsage) / 3)
     * 
     * 示例：
     * - 0-999 tokens: Level 0 (log1000(999) ≈ 0.999，取整 = 0)
     * - 1000-999999 tokens: Level 1 (log1000(1000) = 1, log1000(999999) ≈ 1.999，取整 = 1)
     * - 1000000-999999999 tokens: Level 2 (log1000(1000000) = 2, log1000(999999999) ≈ 2.999，取整 = 2)
     * - 1000000000+ tokens: Level 3+ (log1000(1000000000) = 3)
     * 
     * @param tokenUsage Token消耗量
     * @return VIP等级
     */
    public Integer calculateVipLevel(Long tokenUsage) {
        if (tokenUsage == null || tokenUsage <= 0) {
            return 0;
        }
        
        // 计算 log1000(tokenUsage) = log10(tokenUsage) / log10(1000) = log10(tokenUsage) / 3
        // 使用 Math.log10 计算以10为底的对数
        double log1000 = Math.log10(tokenUsage) / 3.0;
        
        // 向下取整
        int vipLevel = (int) Math.floor(log1000);
        
        // 确保等级不为负数
        return Math.max(0, vipLevel);
    }

    /**
     * 检查并更新用户的VIP等级
     * 如果用户的Token消耗量达到了更高等级的要求，自动升级
     * 
     * @param userId 用户ID
     * @return 是否发生了升级
     */
    @Transactional
    public boolean checkAndUpgradeVip(String userId) {
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                log.warn("用户不存在，无法升级VIP等级: userId={}", userId);
                return false;
            }

            // 计算用户的总Token消耗量
            Long totalTokenUsage = getTotalTokenUsage(userId);
            
            // 根据Token消耗量计算应该的VIP等级
            Integer targetVipLevel = calculateVipLevel(totalTokenUsage);
            
            // 获取当前VIP等级
            Integer currentVipLevel = user.getVipLevel();
            if (currentVipLevel == null) {
                currentVipLevel = 0;
            }

            // 如果目标等级高于当前等级，进行升级
            if (targetVipLevel > currentVipLevel) {
                log.info("用户VIP等级升级: userId={}, 当前等级={}, 目标等级={}, Token消耗量={}", 
                    userId, currentVipLevel, targetVipLevel, totalTokenUsage);
                
                User updateUser = new User();
                updateUser.setId(userId);
                updateUser.setVipLevel(targetVipLevel);
                userMapper.updateById(updateUser);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("检查并更新用户VIP等级失败: userId={}", userId, e);
            return false;
        }
    }
}

