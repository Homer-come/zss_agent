package com.sisi.assistant.service;

import com.sisi.assistant.agent.life.LifeAgent;
import com.sisi.assistant.agent.memory.MemoryAgent;
import com.sisi.assistant.agent.memory.MemoryExtractor;
import com.sisi.assistant.agent.memory.SessionStateManager;
import com.sisi.assistant.agent.memory.SummaryAgent;
import com.sisi.assistant.agent.work.WorkAgent;
import com.sisi.assistant.common.config.AssistantProperties;
import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.common.dto.ChatChunk;
import com.sisi.assistant.common.dto.ChatRequest;
import com.sisi.assistant.common.dto.RouteResult;
import com.sisi.assistant.router.IntentRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 对话编排入口：全链路上下文压缩与分层记忆系统的核心调度器。
 *
 * 职责（按执行顺序）：
 * 1. 通过 PromptBuilder 统一组装上下文（短期窗口 + 会话状态 + 长期记忆RAG + 用户画像 + 历史摘要）；
 * 2. 调用 IntentRouter 决定进入工作模块还是生活模块；
 * 3. 透传 Agent 的 Flux<ChatChunk> 给 Controller 做 SSE；
 * 4. 流结束后异步执行后置处理链：
 *    a. 保存原始聊天记录（带会话关联）；
 *    b. 更新会话状态（主题、情绪、消息序号）；
 *    c. LLM 记忆提取（不阻塞主流程）；
 *    d. 滚动摘要检查与触发。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final IntentRouter intentRouter;
    private final WorkAgent workAgent;
    private final LifeAgent lifeAgent;
    private final MemoryAgent memoryAgent;
    private final MemoryExtractor memoryExtractor;
    private final SessionStateManager sessionStateManager;
    private final SummaryAgent summaryAgent;
    private final PromptBuilder promptBuilder;
    private final AssistantProperties assistantProperties;

    public ConversationService(IntentRouter intentRouter,
                               WorkAgent workAgent,
                               LifeAgent lifeAgent,
                               MemoryAgent memoryAgent,
                               MemoryExtractor memoryExtractor,
                               SessionStateManager sessionStateManager,
                               SummaryAgent summaryAgent,
                               PromptBuilder promptBuilder,
                               AssistantProperties assistantProperties) {
        this.intentRouter = intentRouter;
        this.workAgent = workAgent;
        this.lifeAgent = lifeAgent;
        this.memoryAgent = memoryAgent;
        this.memoryExtractor = memoryExtractor;
        this.sessionStateManager = sessionStateManager;
        this.summaryAgent = summaryAgent;
        this.promptBuilder = promptBuilder;
        this.assistantProperties = assistantProperties;
    }

    /**
     * 一次用户消息的总编排入口。
     * 全链路：Prompt 组装 → 路由 → 流式生成 → 后置处理（保存+提取+摘要）。
     */
    public Flux<ChatChunk> stream(ChatRequest request) {
        String sessionId = request.sessionId();
        String userMessage = request.message();

        // 1. Prompt 组装（替代原来的 compactContext + 各Agent自行拼Prompt）
        RouteResult route = intentRouter.route(userMessage);
        PromptBuilder.PromptAssembly prompt = promptBuilder.assemble(
                userMessage, sessionId, request.context(), route.route());

        // 2. 路由到Agent（Agent不再自己拼Prompt）
        StringBuilder assistantText = new StringBuilder();
        Flux<ChatChunk> routed = route.route() == AgentRoute.WORK
                ? workAgent.handleWithPrompt(prompt, userMessage)
                : lifeAgent.handleWithPrompt(prompt, userMessage);

        return Flux.concat(
                        Flux.just(ChatChunk.status(route.route(), "Intent Router → " + route.route() + " | " + route.reason())),
                        routed
                )
                .doOnNext(chunk -> {
                    if ("text".equals(chunk.type())) {
                        assistantText.append(chunk.content());
                    }
                })
                .doOnComplete(() -> postProcess(sessionId, route.route().name(),
                        userMessage, assistantText.toString()));
    }

    /**
     * 对话后置处理链：所有操作都不阻塞主流程。
     * 按优先级依次执行：保存记录 → 更新状态 → 记忆提取 → 摘要检查。
     */
    private void postProcess(String sessionId, String routeName,
                              String userMessage, String assistantMessage) {
        // 3a. 保存原始聊天记录（带会话关联）
        memoryAgent.saveChat(routeName, userMessage, assistantMessage, sessionId);

        // 3b. 更新会话状态
        sessionStateManager.updateState(sessionId, routeName);

        // 3c. 异步记忆提取（不阻塞主流程，失败静默降级）
        if (assistantProperties.isImportanceExtractEnabled()) {
            memoryExtractor.extractAndSave(routeName, userMessage, assistantMessage, sessionId)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            results -> {
                                if (!results.isEmpty()) {
                                    log.debug("异步提取了 {} 条记忆 (sessionId={})", results.size(), sessionId);
                                }
                            },
                            error -> log.warn("异步记忆提取失败 (sessionId={})", sessionId, error)
                    );
        }

        // 3d. 检查是否需要触发滚动摘要
        summaryAgent.checkAndSummarize(sessionId);
    }
}
