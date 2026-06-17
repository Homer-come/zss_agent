package com.sisi.assistant.common.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        String message,
        String sessionId,
        List<ChatMessage> context
) {
    public record ChatMessage(
            String role,
            String route,
            String content
    ) {
    }
}
