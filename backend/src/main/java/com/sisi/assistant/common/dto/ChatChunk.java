package com.sisi.assistant.common.dto;

public record ChatChunk(
        String type,
        AgentRoute route,
        String content,
        boolean done
) {
    public static ChatChunk status(AgentRoute route, String content) {
        return new ChatChunk("status", route, content, false);
    }

    public static ChatChunk text(AgentRoute route, String content) {
        return new ChatChunk("text", route, content, false);
    }

    public static ChatChunk done(AgentRoute route) {
        return new ChatChunk("done", route, "", true);
    }
}
