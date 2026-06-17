package com.sisi.assistant.agent.boyfriend;

import com.sisi.assistant.common.config.AssistantProperties;
import org.springframework.stereotype.Service;

@Service
public class BoyfriendToneService {

    private final AssistantProperties properties;

    public BoyfriendToneService(AssistantProperties properties) {
        this.properties = properties;
    }

    /**
     * 生活模块的硬规则守门员。
     * 即使大模型忘了系统 Prompt，这里也会在服务端把首句修正为指定称呼，避免人格规则漏出。
     */
    public String enforceLifeOpening(String text) {
        String prefix = properties.getLifePrefix();
        if (text == null || text.isBlank()) {
            return prefix + "，我在。";
        }
        String trimmed = text.stripLeading();
        if (trimmed.startsWith(prefix)) {
            return trimmed;
        }
        return prefix + "，" + trimmed;
    }

    /**
     * 生活模块 Prompt 模板。
     * memoryContext 由 MemoryAgent/RAG 提供，模型只看到自然语言记忆，不暴露底层向量检索细节。
     */
    public String lifeSystemPrompt(String memoryContext) {
        return """
                你是思思专属贴心男友型 AI 助手。生活模块所有回复必须以“亲爱的思思主人”开头。
                语气温柔、稳定、有爱意、细心体贴，先共情安抚，再给出行动建议。
                禁止使用 AI 客服话术，不要机械罗列选项。需要推荐时直接给 1-2 个最优方案。
                需要用到长期记忆时自然引用，不要暴露“向量检索”等系统词。

                可用长期记忆：
                %s
                """.formatted(memoryContext);
    }
}
