package com.sisi.assistant.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sisi.assistant.rag.DeepSeekEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({
        AssistantProperties.class,
        DeepSeekProperties.class,
        FirecrawlProperties.class
})
public class AppConfig {

    /**
     * Enables async cache mode so that @Cacheable on reactive (Mono/Flux)
     * methods works correctly with Caffeine (e.g. FirecrawlSearchService).
     */
    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAsyncCacheMode(true);
        manager.setCacheSpecification("maximumSize=500,expireAfterWrite=10m");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel deepSeekEmbeddingModel(DeepSeekProperties properties,
                                                  ObjectMapper objectMapper,
                                                  WebClient.Builder builder,
                                                  @Value("${spring.ai.vectorstore.milvus.embeddingDimension:1536}")
                                                  int embeddingDimension) {
        return new DeepSeekEmbeddingModel(properties, objectMapper, builder, embeddingDimension);
    }
}
