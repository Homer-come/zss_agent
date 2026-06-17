package com.sisi.assistant.agent.life;

import com.sisi.assistant.agent.boyfriend.BoyfriendToneService;
import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.common.dto.ChatChunk;
import com.sisi.assistant.service.DeepSeekClient;
import com.sisi.assistant.service.PromptBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 生活模块 Agent。
 * 核心改动：不再自行调用 MemoryAgent 和 BoyfriendToneService 拼接 Prompt，
 * 而是接收 PromptBuilder 预组装好的 PromptAssembly，专注于流式调用 LLM 和首句称呼修正。
 */
@Service
public class LifeAgent {

    private final BoyfriendToneService toneService;
    private final DeepSeekClient deepSeekClient;

    public LifeAgent(BoyfriendToneService toneService, DeepSeekClient deepSeekClient) {
        this.toneService = toneService;
        this.deepSeekClient = deepSeekClient;
    }

    /**
     * 使用 PromptBuilder 预组装的 Prompt 进行流式对话。
     * 保留了 enforceLifeOpening 硬规则守门，确保人格一致性。
     */
    public Flux<ChatChunk> handleWithPrompt(PromptBuilder.PromptAssembly prompt, String userMessage) {
        String fallback = toneService.enforceLifeOpening("""
                我听见啦。希望你要开心哦，然后我可以为您做点什么呢？

                按现在的信息，我建议先选最稳妥的一步：如果是想吃点东西，就来一杯热奶茶配清淡主食；如果是心里累，就先把今天最烦的一件事告诉我，我陪你拆开。
                """);

        return Flux.concat(
                Flux.just(ChatChunk.status(AgentRoute.LIFE, "正在检索长期记忆和用户画像，帮你给出更贴心的回应...")),
                deepSeekClient.stream(prompt.systemPrompt(), prompt.userPrompt(), fallback)
                        .index()
                        .map(tuple -> {
                            // 只对首个文本 chunk 执行开头称呼修正，避免每个分片都重复。
                            String text = tuple.getT1() == 0
                                    ? toneService.enforceLifeOpening(tuple.getT2())
                                    : tuple.getT2();
                            return ChatChunk.text(AgentRoute.LIFE, text);
                        }),
                Flux.just(ChatChunk.done(AgentRoute.LIFE))
        );
    }
}
