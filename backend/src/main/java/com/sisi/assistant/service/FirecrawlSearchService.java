package com.sisi.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sisi.assistant.common.config.FirecrawlProperties;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Service
public class FirecrawlSearchService {

    private final FirecrawlProperties properties;
    private final WebClient webClient;

    public FirecrawlSearchService(FirecrawlProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.webClient = builder.baseUrl("https://api.firecrawl.dev").build();
    }

    @Cacheable(cacheNames = "firecrawlSearch", key = "#query")
    /**
     * 工作模块的联网增强入口。
     * 使用缓存是为了避免同一主题反复搜索，降低延迟和外部 API 成本。
     */
    public Mono<String> search(String query) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getApiKey())) {
            return Mono.just("Firecrawl 未配置，已启用本地知识降级；接入 FIRECRAWL_API_KEY 后会自动联网增强。");
        }
        return webClient.post()
                .uri("/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.getApiKey()))
                .bodyValue(Map.of("query", query, "limit", 5))
                .retrieve()
                .bodyToMono(JsonNode.class)
                // 搜索增强不能拖垮主对话，超时后直接降级。
                .timeout(Duration.ofSeconds(8))
                .map(this::summarize)
                .onErrorReturn("联网搜索暂时失败，已降级为本地生成，不影响本次输出。");
    }

    /**
     * 将搜索结果压缩成 Prompt 友好的 bullet list。
     */
    private String summarize(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return "未搜索到高相关背景资料。";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(5, data.size()); i++) {
            JsonNode item = data.get(i);
            builder.append("- ")
                    .append(item.path("title").asText("Untitled"))
                    .append(": ")
                    .append(item.path("description").asText(item.path("url").asText("")))
                    .append('\n');
        }
        return builder.toString();
    }
}
