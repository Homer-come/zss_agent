package com.sisi.assistant.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record MemoryRequest(
        @NotNull MemoryType type,
        @NotBlank String title,
        @NotBlank String content,
        LocalDate eventDate,
        String emotionalTone,
        Integer importance,
        String sessionId
) {
    /**
     * 兼容旧调用的便捷构造：不传 sessionId。
     */
    public MemoryRequest(@NotNull MemoryType type, @NotBlank String title,
                         @NotBlank String content, LocalDate eventDate,
                         String emotionalTone, Integer importance) {
        this(type, title, content, eventDate, emotionalTone, importance, null);
    }
}
