package com.sisi.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sisi.assistant.common.config.DeepSeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DeepSeekClient(DeepSeekProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * 调用 DeepSeek Chat Completions 的流式接口。
     * 返回 Flux<String>，上层 Agent 再把文本片段包装成 ChatChunk，通过 SSE 推给前端。
     */
    public Flux<String> stream(String systemPrompt, String userPrompt, String fallback) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getApiKey())) {
            return localFallback(fallback);
        }

        // 使用 OpenAI-compatible 消息格式：system 负责角色/规则，user 负责本轮任务和上下文。
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> headers.setBearerAuth(properties.getApiKey()))
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                // DeepSeek 的流式响应是 SSE data 行，需要先切行，再解析 delta.content。
                .flatMapIterable(this::splitSseLines)
                .map(this::extractContent)
                .filter(StringUtils::hasText)
                .onErrorResume(ex -> {
                    log.warn("DeepSeek API call failed, using local fallback", ex);
                    return localFallback(fallback + "\n\n（模型暂时不可用，我先用本地降级方案稳稳接住这次对话。）");
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("DeepSeek API returned empty response (no text chunks), using fallback");
                    return fallback;
                }));
    }

    /**
     * 将服务端事件流中的 data: {...} 行拆出来，并过滤 [DONE]。
     */
    private List<String> splitSseLines(String raw) {
        return raw.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(line -> !line.equals("[DONE]"))
                .toList();
    }

    /**
     * 从 OpenAI-compatible chunk 中提取 choices[0].delta.content。
     */
    private String extractContent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 本地降级也拆成多个片段，确保前端仍然看到”流式输出”的体验。
     */
    private Flux<String> localFallback(String content) {
        String safe = StringUtils.hasText(content) ? content : "我在，这次先用本地降级回复陪你把事情处理好。";
        return Flux.fromArray(safe.split("(?<=[。！？\\n])"));
    }

    /**
     * 非流式同步调用 DeepSeek Chat Completions。
     * 用于记忆提取、对话摘要等后置处理场景，这些场景需要完整响应而非流式片段。
     * 供 MemoryExtractor、SummaryAgent 等异步调用，不参与 SSE 主流程。
     */
    public Mono<String> chat(String systemPrompt, String userPrompt, String fallback) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getApiKey())) {
            return Mono.just(fallback != null ? fallback : "");
        }

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.getApiKey()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> root.path("choices").path(0).path("message").path("content").asText(""))
                .filter(StringUtils::hasText)
                .defaultIfEmpty(fallback != null ? fallback : "")
                .onErrorResume(ex -> {
                    log.warn("DeepSeek non-streaming chat failed, using fallback", ex);
                    return Mono.just(fallback != null ? fallback : "");
                });
    }
}
