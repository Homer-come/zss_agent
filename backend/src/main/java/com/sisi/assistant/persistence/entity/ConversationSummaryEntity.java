package com.sisi.assistant.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("conversation_summary")
public class ConversationSummaryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String summaryType;
    private String content;
    private Integer messageRangeStart;
    private Integer messageRangeEnd;
    private Integer tokenEstimate;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSummaryType() {
        return summaryType;
    }

    public void setSummaryType(String summaryType) {
        this.summaryType = summaryType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getMessageRangeStart() {
        return messageRangeStart;
    }

    public void setMessageRangeStart(Integer messageRangeStart) {
        this.messageRangeStart = messageRangeStart;
    }

    public Integer getMessageRangeEnd() {
        return messageRangeEnd;
    }

    public void setMessageRangeEnd(Integer messageRangeEnd) {
        this.messageRangeEnd = messageRangeEnd;
    }

    public Integer getTokenEstimate() {
        return tokenEstimate;
    }

    public void setTokenEstimate(Integer tokenEstimate) {
        this.tokenEstimate = tokenEstimate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
