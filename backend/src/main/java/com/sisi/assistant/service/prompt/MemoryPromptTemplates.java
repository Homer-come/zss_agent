package com.sisi.assistant.service.prompt;

/**
 * Prompts for memory extraction and conversation summaries.
 */
public final class MemoryPromptTemplates {

    private MemoryPromptTemplates() {
    }

    public static String summarySystemPrompt() {
        return """
                你是一个对话摘要助手。将多轮对话历史压缩为一段简洁的摘要。
                要求：
                - 200-400字
                - 保留关键事实、情感变化、未完成的话题和用户的偏好信息
                - 使用第三人称叙述
                - 不要输出"对话摘要"等标题，直接输出摘要正文
                """;
    }

    public static String turnSummaryPrompt(String dialogue) {
        return "请将以下对话压缩为摘要：\n\n%s".formatted(dialogue);
    }

    public static String periodicSummaryPrompt(String dialogue) {
        return "请将以下近期对话压缩为一段周期性摘要：\n\n%s".formatted(dialogue);
    }

    public static String extractSystemPrompt() {
        return """
                你是一个记忆提取助手。分析用户与AI助手的对话，提取值得长期记住的信息。

                如果对话中没有值得记忆的信息（如闲聊、已知的重复信息），返回 [empty]。

                以JSON数组返回，每个元素包含：
                - type: PREFERENCE/EMOTION_TRACE/IMPORTANT_EVENT/RELATIONSHIP/GOAL/PROFILE
                  - PREFERENCE: 用户偏好（饮食、生活习惯、审美等）
                  - EMOTION_TRACE: 情绪变化轨迹（情绪波动、心情记录）
                  - IMPORTANT_EVENT: 重要事件（里程碑、特殊经历）
                  - RELATIONSHIP: 感情/人际关系相关（恋爱记录、互动回忆）
                  - GOAL: 人生目标/愿望/计划
                  - PROFILE: 可更新的用户画像信息（职业、所在地等基本信息）
                - title: 简短标题（10字以内）
                - content: 记忆内容（具体、准确）
                - emotionalTone: positive/neutral/negative
                - importanceScore: 0.0-1.0（低于0.6的不值得存储，请尽量准确评估）
                - profileKey: 仅当type为PROFILE时提供，如"职业""饮食偏好"

                只返回JSON数组，不要返回其他文字。
                """;
    }

    public static String extractUserPrompt(String userMessage, String assistantMessage) {
        return """
                请分析以下对话，提取值得长期记住的信息。

                用户: %s
                助手: %s
                """.formatted(userMessage, assistantMessage);
    }
}
