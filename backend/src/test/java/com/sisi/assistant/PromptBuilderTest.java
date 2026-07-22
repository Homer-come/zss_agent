package com.sisi.assistant;

import com.sisi.assistant.agent.memory.MemoryAgent;
import com.sisi.assistant.agent.memory.SessionStateManager;
import com.sisi.assistant.agent.memory.SummaryAgent;
import com.sisi.assistant.agent.memory.UserProfileManager;
import com.sisi.assistant.common.config.AssistantProperties;
import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.common.dto.ChatRequest;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryType;
import com.sisi.assistant.rag.MemorySearchResult;
import com.sisi.assistant.service.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 PromptBuilder 的 5 层组装契约：
 *   1. System Prompt：按路由选择人格模板 + 注入用户画像
 *   2. User Prompt：任务状态 + 长期记忆 + 历史摘要 + 短期窗口 + 当前消息（按顺序）
 *   3. 任意一层缺失不影响其它层
 *   4. 短期窗口按 maxMessages + maxChars 双层裁剪
 */
class PromptBuilderTest {

    private final SessionStateManager sessionStateManager = mock(SessionStateManager.class);
    private final UserProfileManager userProfileManager = mock(UserProfileManager.class);
    private final MemoryAgent memoryAgent = mock(MemoryAgent.class);
    private final SummaryAgent summaryAgent = mock(SummaryAgent.class);
    private final AssistantProperties properties = new AssistantProperties();

    private final PromptBuilder builder = new PromptBuilder(
            sessionStateManager, userProfileManager, memoryAgent, summaryAgent, properties);

    @BeforeEach
    void setUp() {
        when(userProfileManager.formatForPrompt()).thenReturn("用户画像：思思，22岁。");
        when(sessionStateManager.formatForPrompt(anyString())).thenReturn("主题：生日；情绪：开心");
        when(memoryAgent.retrieve(anyString())).thenReturn(List.of());
        when(memoryAgent.formatForPrompt(org.mockito.ArgumentMatchers.<List<MemorySearchResult>>any()))
                .thenReturn("暂无可用长期记忆。");
        when(summaryAgent.loadLatestSummary(anyString())).thenReturn("");
    }

    @Test
    void lifeRouteBuildsLifeSystemPrompt() {
        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "今天想喝奶茶", "sess-1", List.of(), AgentRoute.LIFE);

        // LIFE 路由：System Prompt 应当包含"亲爱的思思主人"前缀 + 用户画像
        assertThat(prompt.systemPrompt())
                .contains("亲爱的思思主人")
                .contains("用户画像");
    }

    @Test
    void workRouteBuildsWorkSystemPrompt() {
        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "帮我写 PPT 大纲", "sess-1", List.of(), AgentRoute.WORK);

        // WORK 路由：System Prompt 不应该带生活称呼前缀
        assertThat(prompt.systemPrompt())
                .doesNotContain("亲爱的思思主人")
                .contains("用户画像");
    }

    @Test
    void userPromptHasFiveSectionsInOrder() {
        // 准备：长期记忆有一条；历史摘要有一条；短期窗口有 2 条
        MemoryItem mem = new MemoryItem(1L, MemoryType.PREFERENCE, "茉莉奶绿", "三分糖",
                null, "positive", 8, null, null, LocalDateTime.now());
        when(memoryAgent.retrieve(anyString()))
                .thenReturn(List.of(new MemorySearchResult(mem, 0.9)));
        when(memoryAgent.formatForPrompt(org.mockito.ArgumentMatchers.<List<MemorySearchResult>>any()))
                .thenReturn("- [PREFERENCE] 茉莉奶绿: 三分糖\n");
        when(summaryAgent.loadLatestSummary(anyString())).thenReturn("过去聊过几次周末去哪玩。");

        List<ChatRequest.ChatMessage> context = List.of(
                new ChatRequest.ChatMessage("user", "LIFE", "我饿了"),
                new ChatRequest.ChatMessage("assistant", "LIFE", "想吃什么？")
        );

        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "今天想喝奶茶", "sess-1", context, AgentRoute.LIFE);

        String userPrompt = prompt.userPrompt();

        // 五层都出现，且顺序正确
        int idxState = userPrompt.indexOf("【当前任务状态】");
        int idxMemory = userPrompt.indexOf("【长期记忆】");
        int idxSummary = userPrompt.indexOf("【历史摘要】");
        int idxShort = userPrompt.indexOf("【最近对话】");
        int idxCurrent = userPrompt.indexOf("当前用户消息：");

        assertThat(idxState).isGreaterThanOrEqualTo(0);
        assertThat(idxMemory).isGreaterThan(idxState);
        assertThat(idxSummary).isGreaterThan(idxMemory);
        assertThat(idxShort).isGreaterThan(idxSummary);
        assertThat(idxCurrent).isGreaterThan(idxShort);

        // 内容
        assertThat(userPrompt).contains("茉莉奶绿");
        assertThat(userPrompt).contains("过去聊过几次周末去哪玩。");
        assertThat(userPrompt).contains("我饿了");
        assertThat(userPrompt).contains("想吃什么？");
        assertThat(userPrompt).contains("今天想喝奶茶");
    }

    @Test
    void shortTermContextRespectsMaxMessagesAndMaxChars() {
        properties.setMaxContextMessages(2);
        properties.setMaxContextChars(1000);

        List<ChatRequest.ChatMessage> context = new ArrayList<>();
        // 5 条消息，但 maxMessages=2 应该只保留最后 2 条
        for (int i = 0; i < 5; i++) {
            context.add(new ChatRequest.ChatMessage("user", "LIFE", "msg-" + i));
        }

        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "test", "s", context, AgentRoute.LIFE);

        assertThat(prompt.userPrompt())
                .contains("msg-3")
                .contains("msg-4")
                .doesNotContain("msg-0")
                .doesNotContain("msg-2");
    }

    @Test
    void shortTermContextTruncatesOversizedContent() {
        properties.setMaxContextMessages(10);
        properties.setMaxContextChars(50);

        List<ChatRequest.ChatMessage> context = List.of(
                new ChatRequest.ChatMessage("user", "LIFE", "a".repeat(200))
        );

        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "test", "s", context, AgentRoute.LIFE);

        // 单条消息被截断到 50 字符以内
        String section = prompt.userPrompt().substring(prompt.userPrompt().indexOf("【最近对话】"));
        assertThat(section).hasSizeLessThan(400); // 留余量给框架文字
        assertThat(section).doesNotContain("a".repeat(100));
    }

    @Test
    void emptyContextProducesFriendlyDefault() {
        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "hi", "s", List.of(), AgentRoute.LIFE);

        assertThat(prompt.userPrompt()).contains("暂无短期上下文。");
    }

    @Test
    void disabledContextProducesExplicitMessage() {
        properties.setMaxContextMessages(0);

        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "hi", "s",
                List.of(new ChatRequest.ChatMessage("user", "LIFE", "x")),
                AgentRoute.LIFE);

        assertThat(prompt.userPrompt()).contains("短期上下文已关闭。");
    }

    @Test
    void emptySummarySectionOmitted() {
        // loadLatestSummary 返回空：摘要小节应被省略而不是显示"暂无"
        PromptBuilder.PromptAssembly prompt = builder.assemble(
                "hi", "s", List.of(), AgentRoute.LIFE);

        assertThat(prompt.userPrompt()).doesNotContain("【历史摘要】");
    }
}