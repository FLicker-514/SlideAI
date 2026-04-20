package com.learning.account.service;

import com.learning.account.entity.UserProfile;
import com.learning.account.mapper.UserProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 用户画像服务（本项目中仅做基础存储，不依赖 LLM/其他微服务）
 */
@Slf4j
@Service
public class UserProfileService {

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Transactional
    public boolean checkAndUpdateProfile(String userId) {
        try {
            UserProfile profile = userProfileMapper.selectById(userId);
            LocalDate today = LocalDate.now();
            if (profile != null && profile.getUpdatedAt() != null && profile.getUpdatedAt().equals(today)) {
                return false;
            }
            String content = "暂无画像";
            if (profile == null) {
                profile = new UserProfile();
                profile.setUserId(userId);
                profile.setProfileContent(content);
                profile.setUpdatedAt(today);
                profile.setCreatedAt(today);
                userProfileMapper.insert(profile);
            } else {
                profile.setProfileContent(content);
                profile.setUpdatedAt(today);
                userProfileMapper.updateById(profile);
            }
            return true;
        } catch (Exception e) {
            log.error("更新用户画像失败: userId={}", userId, e);
            return false;
        }
    }

    public String getUserProfile(String userId) {
        try {
            UserProfile profile = userProfileMapper.selectById(userId);
            return profile != null ? profile.getProfileContent() : null;
        } catch (Exception e) {
            log.error("获取用户画像失败: userId={}", userId, e);
            return null;
        }
    }
}
