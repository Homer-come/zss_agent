package com.sisi.assistant.agent.work;

import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.common.dto.ChatChunk;
import com.sisi.assistant.service.DeepSeekClient;
import com.sisi.assistant.service.FirecrawlSearchService;
import com.sisi.assistant.service.PromptBuilder;
import com.sisi.assistant.service.prompt.WorkPromptTemplates;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Locale;

/**
 * 工作模块 Agent。
 * 核心改动：
 * 1. 新增 handleWithPrompt() 方法，使用 PromptBuilder 预组装的 Prompt；
 * 2. handle() 保留兼容（直接搜索+拼接），供简单场景使用；
 * 3. 新增 handleWithPromptAndSearch() 将 PromptBuilder 的上下文与 Firecrawl 背景合并。
 */
@Service
public class WorkAgent {

    private final DeepSeekClient deepSeekClient;
    private final FirecrawlSearchService firecrawlSearchService;

    public WorkAgent(DeepSeekClient deepSeekClient, FirecrawlSearchService firecrawlSearchService) {
        this.deepSeekClient = deepSeekClient;
        this.firecrawlSearchService = firecrawlSearchService;
    }

    /**
     * 使用 PromptBuilder 预组装的 Prompt + Firecrawl 背景资料进行流式对话。
     * 将长期记忆和用户画像与联网背景合并，提供给 LLM 更全面的信息。
     */
    public Flux<ChatChunk> handleWithPrompt(PromptBuilder.PromptAssembly prompt, String userMessage) {
        return firecrawlSearchService.search(userMessage)
                .flatMapMany(background -> {
                    // 将 PromptBuilder 的 userPrompt 与 Firecrawl 背景合并
                    String enhancedPrompt = prompt.userPrompt() + "\n\n【联网背景资料】\n" + background;
                    String fallback = buildFallback(userMessage, background);
                    return Flux.concat(
                            Flux.just(ChatChunk.status(AgentRoute.WORK, "正在通过 Firecrawl 检索背景资料并组织内容结构...")),
                            deepSeekClient.stream(prompt.systemPrompt(), enhancedPrompt, fallback)
                                    .map(text -> ChatChunk.text(AgentRoute.WORK, text)),
                            Flux.just(ChatChunk.done(AgentRoute.WORK))
                    );
                });
    }

    /**
     * 模型不可用时的本地降级文案。
     * Agent 系统要避免把外部模型故障直接暴露给用户，至少提供一个结构正确的兜底结果。
     */
    private String buildFallback(String userMessage, String background) {
        if (matchesAny(userMessage, "小红书", "种草", "文案", "笔记", "宣传")) {
            return WorkPromptTemplates.copyFallback(
                    buildCopyTitle(userMessage),
                    buildCopyBody(userMessage, background));
        }

        if (matchesAny(userMessage, "ppt", "大纲", "汇报", "总结", "提纲", "方案")) {
            return WorkPromptTemplates.pptFallback(background);
        }

        if (matchesAny(userMessage, "改写", "润色", "扩写", "翻译", "优化")) {
            return WorkPromptTemplates.rewriteFallback(buildRewriteBody(userMessage), background);
        }

        return WorkPromptTemplates.generalFallback(buildGeneralBody(userMessage), background);
    }

    private boolean matchesAny(String input, String... keywords) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String buildCopyTitle(String userMessage) {
        if (matchesAny(userMessage, "小红书")) {
            return "把平凡日常写成想点进来的种草感";
        }
        return "一段有画面感、能直接发布的文案";
    }

    private String buildCopyBody(String userMessage, String background) {
        return WorkPromptTemplates.copyBody(userMessage, buildBackgroundHint(background));
    }

    private String buildRewriteBody(String userMessage) {
        return WorkPromptTemplates.rewriteBody(userMessage);
    }

    private String buildGeneralBody(String userMessage) {
        return WorkPromptTemplates.generalBody(userMessage);
    }

    private String buildBackgroundHint(String background) {
        if (!StringUtils.hasText(background)) {
            return "如果没有额外背景，也可以直接从使用体验、情绪点和结果感切入。";
        }
        return "顺手参考这些信息也行：\n" + background;
    }
}
