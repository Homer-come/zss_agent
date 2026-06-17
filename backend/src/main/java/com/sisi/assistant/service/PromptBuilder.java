package com.sisi.assistant.service;

import com.sisi.assistant.agent.memory.MemoryAgent;
import com.sisi.assistant.agent.memory.SessionStateManager;
import com.sisi.assistant.agent.memory.SummaryAgent;
import com.sisi.assistant.agent.memory.UserProfileManager;
import com.sisi.assistant.common.config.AssistantProperties;
import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.common.dto.ChatRequest;
import com.sisi.assistant.rag.MemorySearchResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 统一 Prompt 组装器：替代原先散落在 LifeAgent 和 WorkAgent 中的 Prompt 拼接逻辑。
 *
 * 组装顺序（由远到近，由全局到具体）：
 * 1. System Prompt（人格模板 + 用户画像）
 * 2. User Prompt:
 *    a. 当前任务状态（Session State）
 *    b. 长期记忆（RAG 检索结果）
 *    c. 历史摘要（如果有滚动摘要）
 *    d. 短期上下文（最近N轮对话窗口）
 *    e. 当前用户消息
 *
 * 所有上下文层都独立可控，任何一层缺失都不影响其他层的组装。
 */
@Service
public class PromptBuilder {

    private final SessionStateManager sessionStateManager;
    private final UserProfileManager userProfileManager;
    private final MemoryAgent memoryAgent;
    private final SummaryAgent summaryAgent;
    private final AssistantProperties properties;

    public PromptBuilder(SessionStateManager sessionStateManager,
                         UserProfileManager userProfileManager,
                         MemoryAgent memoryAgent,
                         SummaryAgent summaryAgent,
                         AssistantProperties properties) {
        this.sessionStateManager = sessionStateManager;
        this.userProfileManager = userProfileManager;
        this.memoryAgent = memoryAgent;
        this.summaryAgent = summaryAgent;
        this.properties = properties;
    }

    /**
     * 组装后的完整 Prompt 结构。
     */
    public record PromptAssembly(
            String systemPrompt,
            String userPrompt
    ) {}

    /**
     * 根据用户消息、会话、原始上下文和路由，组装完整的 Prompt。
     * 这是整个上下文压缩系统的核心入口。
     */
    public PromptAssembly assemble(String userMessage, String sessionId,
                                    List<ChatRequest.ChatMessage> rawContext, AgentRoute route) {
        // 1. System Prompt：根据路由选择人格模板
        String userProfile = userProfileManager.formatForPrompt();
        String systemPrompt = buildSystemPrompt(route, userProfile);

        // 2. User Prompt：分层组装上下文
        StringBuilder userPrompt = new StringBuilder();

        // 2a. 当前任务状态
        String sessionState = sessionStateManager.formatForPrompt(sessionId);
        userPrompt.append("【当前任务状态】\n").append(sessionState).append('\n');

        // 2b. 长期记忆（RAG 检索）
        List<MemorySearchResult> memories = memoryAgent.retrieve(userMessage);
        String memoryContext = memoryAgent.formatForPrompt(memories);
        userPrompt.append("【长期记忆】\n").append(memoryContext).append('\n');

        // 2c. 历史摘要
        String summary = summaryAgent.loadLatestSummary(sessionId);
        if (StringUtils.hasText(summary) && !"暂无用户画像信息。".equals(summary)) {
            userPrompt.append("【历史摘要】\n").append(summary).append('\n');
        }

        // 2d. 短期上下文（滑动窗口）
        String shortTermContext = compactContext(rawContext);
        userPrompt.append("【最近对话】\n").append(shortTermContext).append('\n');

        // 2e. 当前用户消息
        userPrompt.append("当前用户消息：").append(userMessage);

        return new PromptAssembly(systemPrompt, userPrompt.toString());
    }

    /**
     * 构建系统 Prompt。根据路由选择不同的人格模板。
     * 用户画像始终注入到 system prompt 中，让模型从一开始就"认识"用户。
     */
    private String buildSystemPrompt(AgentRoute route, String userProfile) {
        if (route == AgentRoute.LIFE) {
            return """
                    你是思思专属贴心男友型 AI 助手。生活模块所有回复必须以"%s"开头。
                    语气温柔、稳定、有爱意、细心体贴，先共情安抚，再给出行动建议。
                    禁止使用 AI 客服话术，不要机械罗列选项。需要推荐时直接给 1-2 个最优方案。
                    需要用到长期记忆时自然引用，不要暴露"向量检索"等系统词。

                    用户画像：
                    %s
                    """.formatted(properties.getLifePrefix(), userProfile);
        }

        // WORK 及默认路由
        return """
                你是资深内容专家与职场提效助手。必须专业、干练、结构清晰。
                直接交付结果，不要输出"任务理解""背景增强""建议结构"这种调试式分段。
                用户要 PPT 时，输出可直接复制的金字塔结构提纲。
                用户要文案时，直接给成稿，优先满足目标平台和语气。
                输出使用 Markdown 标题、列表、加粗和引用，方便前端渲染。

                用户画像：
                %s
                """.formatted(userProfile);
    }

    /**
     * 将前端传来的最近聊天上下文压缩成 Prompt 可读文本。
     * 窗口大小和字符预算由配置驱动，Agent 开发里通常需要同时控制"消息条数"和"总字符数"。
     */
    private String compactContext(List<ChatRequest.ChatMessage> context) {
        if (context == null || context.isEmpty()) {
            return "暂无短期上下文。";
        }

        int maxMessages = Math.max(0, properties.getMaxContextMessages());
        int maxChars = Math.max(0, properties.getMaxContextChars());
        if (maxMessages == 0 || maxChars == 0) {
            return "短期上下文已关闭。";
        }

        List<ChatRequest.ChatMessage> recent = context.stream()
                .filter(message -> message != null && StringUtils.hasText(message.content()))
                .skip(Math.max(0, context.size() - maxMessages))
                .toList();
        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (ChatRequest.ChatMessage message : recent) {
            String role = StringUtils.hasText(message.role()) ? message.role() : "unknown";
            String route = StringUtils.hasText(message.route()) ? message.route() : "GENERAL";
            String content = message.content().trim();
            int remaining = maxChars - used;
            if (remaining <= 0) {
                break;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }
            builder.append("- ").append(role).append("[").append(route).append("]: ")
                    .append(content).append('\n');
            used += content.length();
        }
        return builder.isEmpty() ? "暂无短期上下文。" : builder.toString();
    }
}
