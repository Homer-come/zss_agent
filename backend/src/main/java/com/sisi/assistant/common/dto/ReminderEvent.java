package com.sisi.assistant.common.dto;

import java.time.LocalDateTime;

public record ReminderEvent(
        String type,
        String title,
        String message,
        LocalDateTime createdAt
) {
    public static ReminderEvent of(String type, String title, String message) {
        return new ReminderEvent(type, title, message, LocalDateTime.now());
    }
}
