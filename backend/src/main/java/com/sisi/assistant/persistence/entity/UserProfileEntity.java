package com.sisi.assistant.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_profile")
public class UserProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String profileKey;
    private String profileValue;
    private Integer confidence;
    private Long sourceMemoryId;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProfileKey() {
        return profileKey;
    }

    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey;
    }

    public String getProfileValue() {
        return profileValue;
    }

    public void setProfileValue(String profileValue) {
        this.profileValue = profileValue;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public Long getSourceMemoryId() {
        return sourceMemoryId;
    }

    public void setSourceMemoryId(Long sourceMemoryId) {
        this.sourceMemoryId = sourceMemoryId;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
