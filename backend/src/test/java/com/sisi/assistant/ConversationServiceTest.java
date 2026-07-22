package com.sisi.assistant;

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
import com.sisi.assistant.service.ConversationService;
import com.sisi.assistant.service.PromptBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证对话总编排入口的核心契约：
 *   1. PromptBuilder 先组装 Prompt
 *   2. 按路由分派到 WorkAgent / LifeAgent
 *   3. 流结束触发后置处理链：saveChat -> updateState -> extract -> summarize
 *   4. 后置处理中任何失败都不能影响 SSE 主流程
 */
class ConversationServiceTest {

    private final IntentRouter intentRouter = mock(IntentRouter.class);
    private final WorkAgent workAgent = mock(WorkAgent.class);
    private final LifeAgent lifeAgent = mock(LifeAgent.class);
    private final MemoryAgent memoryAgent = mock(MemoryAgent.class);
    private final MemoryExtractor memoryExtractor = mock(MemoryExtractor.class);
    private final SessionStateManager sessionStateManager = mock(SessionStateManager.class);
    private final SummaryAgent summaryAgent = mock(SummaryAgent.class);
    private final PromptBuilder promptBuilder = mock(PromptBuilder.class);
    private final AssistantProperties properties = new AssistantProperties();

    private final ConversationService service = new ConversationService(
            intentRouter, workAgent, lifeAgent, memoryAgent,
            memoryExtractor, sessionStateManager, summaryAgent,
            promptBuilder, properties);

    @Test
    void routesWorkAgentAndTriggersPostProcessOnComplete() {
        // given：路由到 Work + 模拟 prompt + 流式输出
        when(intentRouter.route("帮我写个 PPT 大纲"))
                .thenReturn(new RouteResult(AgentRoute.WORK, 0.8, "命中工作意图"));
        when(promptBuilder.assemble(anyString(), anyString(), any(), any()))
                .thenReturn(new PromptBuilder.PromptAssembly("system", "user"));
        when(workAgent.handleWithPrompt(any(), anyString()))
                .thenReturn(Flux.just(
                        ChatChunk.text(AgentRoute.WORK, "要点1；"),
                        ChatChunk.text(AgentRoute.WORK, "要点2。"),
                        ChatChunk.done(AgentRoute.WORK)
                ));
        when(memoryExtractor.extractAndSave(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(List.of()));

        ChatRequest req = new ChatRequest(
                "帮我写个 PPT 大纲", "sess-A",
                List.of(new ChatRequest.ChatMessage("user", "WORK", "hello"))
        );

        // when + then：状态 chunk 先于文本 chunk 到达；流结束触发后置处理
        StepVerifier.create(service.stream(req).collectList())
                .assertNext(chunks -> {
                    assertThat(chunks).hasSize(4);
                    assertThat(chunks.get(0).type()).isEqualTo("status");
                    assertThat(chunks.get(0).route()).isEqualTo(AgentRoute.WORK);
                    assertThat(chunks.get(1).content()).isEqualTo("要点1；");
                    assertThat(chunks.get(2).content()).isEqualTo("要点2。");
                    assertThat(chunks.get(3).done()).isTrue();
                })
                .verifyComplete();

        // 后置处理链：saveChat 写入路由名 = WORK
        verify(memoryAgent).saveChat(eq("WORK"), eq("帮我写个 PPT 大纲"),
                eq("要点1；要点2。"), eq("sess-A"));
        verify(sessionStateManager).updateState(eq("sess-A"), eq("WORK"));
        verify(memoryExtractor).extractAndSave(eq("WORK"), eq("帮我写个 PPT 大纲"),
                eq("要点1；要点2。"), eq("sess-A"));
        verify(summaryAgent).checkAndSummarize(eq("sess-A"));
        // LifeAgent 不应被调用
        verify(lifeAgent, never()).handleWithPrompt(any(), anyString());
    }

    @Test
    void routesLifeAgentAndAggregatesText() {
        when(intentRouter.route("今天有点累"))
                .thenReturn(new RouteResult(AgentRoute.LIFE, 0.7, "命中生活意图"));
        when(promptBuilder.assemble(anyString(), anyString(), any(), any()))
                .thenReturn(new PromptBuilder.PromptAssembly("system-life", "user-life"));
        when(lifeAgent.handleWithPrompt(any(), anyString()))
                .thenReturn(Flux.just(
                        ChatChunk.text(AgentRoute.LIFE, "亲爱的思思主人，"),
                        ChatChunk.text(AgentRoute.LIFE, "我在，先喝口热水。"),
                        ChatChunk.done(AgentRoute.LIFE)
                ));
        when(memoryExtractor.extractAndSave(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(List.of()));

        ChatRequest req = new ChatRequest("今天有点累", "sess-B", List.of());

        StepVerifier.create(service.stream(req).collectList())
                .assertNext(chunks -> {
                    assertThat(chunks).hasSize(4);
                    assertThat(chunks.get(0).route()).isEqualTo(AgentRoute.LIFE);
                })
                .verifyComplete();

        verify(memoryAgent).saveChat(eq("LIFE"), eq("今天有点累"),
                eq("亲爱的思思主人，我在，先喝口热水。"), eq("sess-B"));
        verify(workAgent, never()).handleWithPrompt(any(), anyString());
    }

    @Test
    void emptyAssistantTextStillCallsSaveChat() {
        // 即便模型只返回 done chunk（fallback 全是空字符串），saveChat 也要被调用，便于审计
        when(intentRouter.route("test")).thenReturn(new RouteResult(AgentRoute.LIFE, 0.5, "默认"));
        when(promptBuilder.assemble(anyString(), anyString(), any(), any()))
                .thenReturn(new PromptBuilder.PromptAssembly("s", "u"));
        when(lifeAgent.handleWithPrompt(any(), anyString()))
                .thenReturn(Flux.just(ChatChunk.done(AgentRoute.LIFE)));
        when(memoryExtractor.extractAndSave(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.stream(new ChatRequest("test", "s", List.of())).collectList())
                .verifyComplete();

        ArgumentCaptor<String> assistantCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryAgent).saveChat(eq("LIFE"), eq("test"), assistantCaptor.capture(), eq("s"));
        assertThat(assistantCaptor.getValue()).isEmpty();
    }

    @Test
    void importExtractDisabledSkipsExtractor() {
        // 关闭自动提取后，MemoryExtractor 不应被触发，但 saveChat / summary 仍然要执行
        properties.setImportanceExtractEnabled(false);
        try {
            when(intentRouter.route("x")).thenReturn(new RouteResult(AgentRoute.LIFE, 0.5, "默认"));
            when(promptBuilder.assemble(anyString(), anyString(), any(), any()))
                    .thenReturn(new PromptBuilder.PromptAssembly("s", "u"));
            when(lifeAgent.handleWithPrompt(any(), anyString()))
                    .thenReturn(Flux.just(ChatChunk.done(AgentRoute.LIFE)));

            service.stream(new ChatRequest("x", "s", List.of())).collectList().block();

            verify(memoryAgent, times(1)).saveChat(anyString(), anyString(), anyString(), anyString());
            verify(memoryExtractor, never()).extractAndSave(anyString(), anyString(), anyString(), anyString());
            verify(summaryAgent, times(1)).checkAndSummarize(anyString());
        } finally {
            properties.setImportanceExtractEnabled(true);
        }
    }
}