package com.sisi.assistant.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sisi.assistant.common.config.DeepSeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An EmbeddingModel that calls DeepSeek's OpenAI-compatible /v1/embeddings API.
 * The deepseek-embedding model produces 1536-dimensional vectors.
 */
public class DeepSeekEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekEmbeddingModel.class);
    private static final String EMBEDDING_MODEL = "deepseek-embedding";
    private static final int DEFAULT_DIMENSION = 1536;

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final int dimension;

    public DeepSeekEmbeddingModel(DeepSeekProperties properties,
                                  ObjectMapper objectMapper,
                                  WebClient.Builder builder,
                                  int dimension) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.dimension = dimension > 0 ? dimension : DEFAULT_DIMENSION;
        this.webClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    /**
     * Spring AI VectorStore 会调用这个方法把 Document 转成向量。
     */
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    /**
     * 调用 DeepSeek OpenAI-compatible embeddings 接口。
     * 如果未配置 key 或接口异常，返回零向量以保护主流程；生产环境可改为抛出可观测异常或切换备用模型。
     */
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getApiKey())) {
            log.warn("DeepSeek is disabled or API key not configured; returning zero-vector embedding");
            return fallbackResponse(request.getInstructions());
        }

        Map<String, Object> body = Map.of(
                "model", EMBEDDING_MODEL,
                "input", request.getInstructions()
        );

        List<Embedding> resultList = webClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.getApiKey()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> parseEmbeddings(root, request.getInstructions()))
                .block();

        if (resultList == null) {
            log.warn("DeepSeek embedding API returned null; falling back to zero vectors");
            return fallbackResponse(request.getInstructions());
        }

        return new EmbeddingResponse(resultList);
    }

    /**
     * 将 DeepSeek 返回的 JSON data[].embedding 转成 Spring AI 的 Embedding 对象。
     */
    private List<Embedding> parseEmbeddings(JsonNode root, List<String> inputs) {
        JsonNode data = root.path("data");
        if (data.isMissingNode() || !data.isArray()) {
            log.warn("Unexpected embedding API response structure: {}", root);
            return zeroEmbeddings(inputs);
        }

        List<Embedding> results = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            int index = item.path("index").asInt(0);
            JsonNode embeddingArray = item.path("embedding");
            float[] vector = new float[dimension];
            int idx = 0;
            for (JsonNode v : embeddingArray) {
                if (idx < dimension) {
                    vector[idx++] = (float) v.asDouble();
                }
            }
            results.add(new Embedding(vector, index));
        }
        return results;
    }

    /**
     * 降级响应：保持输入条数和输出条数一致，避免调用方因为数量不匹配失败。
     */
    private EmbeddingResponse fallbackResponse(List<String> inputs) {
        return new EmbeddingResponse(zeroEmbeddings(inputs));
    }

    private List<Embedding> zeroEmbeddings(List<String> inputs) {
        float[] zeroVector = new float[dimension];
        List<Embedding> list = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            list.add(new Embedding(zeroVector.clone(), i));
        }
        return list;
    }

    public int getDimension() {
        return dimension;
    }
}
