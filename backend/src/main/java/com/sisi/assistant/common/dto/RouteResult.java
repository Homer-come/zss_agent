package com.sisi.assistant.common.dto;

public record RouteResult(
        AgentRoute route,
        double confidence,
        String reason
) {
}
