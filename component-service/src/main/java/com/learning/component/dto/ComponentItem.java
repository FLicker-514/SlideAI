package com.learning.component.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentItem {
    private String id;
    private String userId;
    private String name;
    private String description;
    private String htmlFile;
    private String createdAt;
    private String updatedAt;
}

