package com.sisi.assistant.common.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MemoryItem(
        Long id,
        MemoryType type,
        String title,
        String content,
        LocalDate eventDate,
        String emotionalTone,
        int importance,
        String sessionId,
        Double importanceScore,
        LocalDateTime createdAt
) {
    /**
     * 兼容旧调用的便捷构造：不传 sessionId 和 importanceScore。
     * 新代码应使用完整构造器。
     */
    public MemoryItem(Long id, MemoryType type, String title, String content,
                      LocalDate eventDate, String emotionalTone, int importance, LocalDateTime createdAt) {
        this(id, type, title, content, eventDate, emotionalTone, importance, null, null, createdAt);
    }

    /**
     * 用于向量检索和语义匹配的拼接文本。
     * RAG 索引层只看这段文字，不关心底层表结构。
     */
    public String searchableText() {
        return "%s %s %s %s".formatted(type, title, content, eventDate == null ? "" : eventDate);
    }
}
