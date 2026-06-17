package com.sisi.assistant.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("memory_item")
public class MemoryItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String memoryType;
    private String title;
    private String content;
    private LocalDate eventDate;
    private String emotionalTone;
    private Integer importance;
    private String sessionId;
    private Double importanceScore;
    private String embeddingText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public String getEmotionalTone() {
        return emotionalTone;
    }

    public void setEmotionalTone(String emotionalTone) {
        this.emotionalTone = emotionalTone;
    }

    public Integer getImportance() {
        return importance;
    }

    public void setImportance(Integer importance) {
        this.importance = importance;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Double getImportanceScore() {
        return importanceScore;
    }

    public void setImportanceScore(Double importanceScore) {
        this.importanceScore = importanceScore;
    }

    public String getEmbeddingText() {
        return embeddingText;
    }

    public void setEmbeddingText(String embeddingText) {
        this.embeddingText = embeddingText;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
