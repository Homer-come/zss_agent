package com.sisi.assistant.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryRequest;
import com.sisi.assistant.common.dto.MemoryType;
import com.sisi.assistant.service.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆提取器：每轮对话结束后，用 LLM 从对话中提取结构化长期记忆。
 * 设计为异步调用，不阻塞 SSE 主流程。LLM 不可用时静默降级。
 *
 * 提取流程：
 * 1. 将用户消息 + 助手回复组成提取 Prompt；
 * 2. LLM 返回 JSON 数组（每条包含 type/title/content/emotionalTone/importanceScore）；
 * 3. 过滤 importanceScore < threshold 的低价值条目；
 * 4. 调用 MemoryAgent.rememberAll() 批量写入 + RAG 索引。
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final String EXTRACT_SYSTEM_PROMPT = """
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

    private final DeepSeekClient deepSeekClient;
    private final MemoryAgent memoryAgent;
    private final ObjectMapper objectMapper;
    private final double importanceThreshold;

    public MemoryExtractor(DeepSeekClient deepSeekClient,
                           MemoryAgent memoryAgent,
                           ObjectMapper objectMapper,
                           @Value("${sisi.memory.importance-threshold:0.6}") double importanceThreshold) {
        this.deepSeekClient = deepSeekClient;
        this.memoryAgent = memoryAgent;
        this.objectMapper = objectMapper;
        this.importanceThreshold = importanceThreshold;
    }

    /**
     * 异步提取并保存记忆。调用方在 doOnComplete 中 subscribe 即可。
     * 返回 Mono<List<MemoryItem>> 表示实际保存的记忆条目。
     */
    public Mono<List<MemoryItem>> extractAndSave(String route, String userMessage,
                                                 String assistantMessage, String sessionId) {
        String userPrompt = buildExtractPrompt(userMessage, assistantMessage);
        return deepSeekClient.chat(EXTRACT_SYSTEM_PROMPT, userPrompt, null)
                .map(this::parseExtractionResult)
                .flatMap(extracted -> {
                    if (extracted.isEmpty()) {
                        log.debug("本轮对话无值得提取的记忆");
                        return Mono.just(List.<MemoryItem>of());
                    }
                    // 批量写入记忆
                    List<MemoryItem> saved = new ArrayList<>();
                    for (MemoryRequest req : extracted) {
                        try {
                            saved.add(memoryAgent.remember(req));
                        } catch (Exception ex) {
                            log.warn("单条记忆保存失败，不影响其余: {}", req.title(), ex);
                        }
                    }
                    log.info("本轮对话提取并保存了 {} 条记忆 (sessionId={})", saved.size(), sessionId);
                    return Mono.just(saved);
                })
                .onErrorResume(ex -> {
                    log.warn("记忆提取LLM调用失败，静默降级", ex);
                    return Mono.just(List.of());
                });
    }

    /**
     * 将提取结果解析为 MemoryRequest 列表。
     * 支持 [empty] 标记、JSON数组、以及各种异常格式的容错处理。
     */
    private List<MemoryRequest> parseExtractionResult(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equals("[empty]")) {
            return List.of();
        }

        // 提取 JSON 部分（有些模型会在 JSON 前后加 markdown 代码块标记）
        String json = raw.trim();
        if (json.startsWith("```json")) {
            json = json.substring(7);
        }
        if (json.startsWith("```")) {
            json = json.substring(3);
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        json = json.trim();

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }

            List<MemoryRequest> requests = new ArrayList<>();
            for (JsonNode node : root) {
                try {
                    MemoryRequest req = parseSingleExtraction(node);
                    if (req != null) {
                        requests.add(req);
                    }
                } catch (Exception ex) {
                    log.debug("跳过一条无法解析的记忆提取结果: {}", node);
                }
            }
            return requests;
        } catch (JsonProcessingException ex) {
            log.warn("记忆提取结果JSON解析失败: {}", json);
            return List.of();
        }
    }

    /**
     * 解析单条提取结果为 MemoryRequest。
     * 过滤 importanceScore 低于阈值的条目。
     */
    private MemoryRequest parseSingleExtraction(JsonNode node) {
        String typeStr = node.path("type").asText("");
        MemoryType type;
        try {
            type = MemoryType.valueOf(typeStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        String title = node.path("title").asText("");
        String content = node.path("content").asText("");
        if (!title.isBlank() && !content.isBlank()) {
            double score = node.path("importanceScore").asDouble(0.5);
            if (score < importanceThreshold) {
                log.debug("记忆 '{}' 重要性评分 {} 低于阈值 {}，跳过", title, score, importanceThreshold);
                return null;
            }
            String tone = node.path("emotionalTone").asText("neutral");
            int importance = (int) Math.round(score * 10);
            return new MemoryRequest(type, title, content, null, tone, importance, null);
        }
        return null;
    }

    private String buildExtractPrompt(String userMessage, String assistantMessage) {
        return """
                请分析以下对话，提取值得长期记住的信息。

                用户: %s
                助手: %s
                """.formatted(userMessage, assistantMessage);
    }
}
