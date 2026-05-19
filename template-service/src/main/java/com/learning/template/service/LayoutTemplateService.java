package com.learning.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learning.template.entity.LayoutTemplate;
import com.learning.template.mapper.LayoutTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "template.db.enabled", havingValue = "true")
public class LayoutTemplateService {

    private final LayoutTemplateMapper layoutTemplateMapper;

    public List<LayoutTemplate> list() {
        return layoutTemplateMapper.selectList(
                new LambdaQueryWrapper<LayoutTemplate>()
                        .orderByAsc(LayoutTemplate::getSortOrder)
                        .orderByDesc(LayoutTemplate::getCreatedAt)
        );
    }

    public LayoutTemplate getById(Long id) {
        return layoutTemplateMapper.selectById(id);
    }

    public LayoutTemplate create(LayoutTemplate template) {
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        if (template.getSortOrder() == null) {
            template.setSortOrder(0);
        }
        layoutTemplateMapper.insert(template);
        return template;
    }

    public boolean updateById(LayoutTemplate template) {
        template.setUpdatedAt(LocalDateTime.now());
        return layoutTemplateMapper.updateById(template) > 0;
    }

    public boolean deleteById(Long id) {
        return layoutTemplateMapper.deleteById(id) > 0;
    }
}
