package com.sisi.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sisi.assistant.agent.memory.MemoryAgent;
import com.sisi.assistant.agent.memory.MemoryExtractor;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryRequest;
import com.sisi.assistant.common.dto.MemoryType;
import com.sisi.assistant.service.DeepSeekClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证记忆提取器的容错能力，这是异步后台任务的入口，
 * 任何解析失败都不能让 SSE 主流程感知，但合法结果必须正确写入。
 */
class MemoryExtractorTest {

    private final DeepSeekClient deepSeekClient = mock(DeepSeekClient.class);
    private final MemoryAgent memoryAgent = mock(MemoryAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MemoryExtractor extractor = new MemoryExtractor(
            deepSeekClient, memoryAgent, objectMapper, 0.6);

    @Test
    void parsesValidJsonAndSavesMemory() {
        String llmOutput = """
                [
                  {"type":"PREFERENCE","title":"喜欢茉莉奶绿","content":"三分糖少冰","importanceScore":0.8,"emotionalTone":"positive"},
                  {"type":"PROFILE","title":"职业","content":"产品经理","importanceScore":0.7,"emotionalTone":"neutral","profileKey":"职业"}
                ]
                """;
        when(deepSeekClient.chat(anyString(), anyString(), any())).thenReturn(Mono.just(llmOutput));
        when(memoryAgent.remember(any(MemoryRequest.class))).thenAnswer(inv -> {
            MemoryRequest req = inv.getArgument(0);
            return new MemoryItem(1L, req.type(), req.title(), req.content(),
                    req.eventDate(), req.emotionalTone(),
                    req.importance() == null ? 5 : req.importance(),
                    req.sessionId(), null, LocalDateTime.now());
        });

        StepVerifier.create(extractor.extractAndSave("LIFE",
                        "我喜欢喝茉莉奶绿", "记下了", "sess-1"))
                .assertNext(items -> assertThat(items).hasSize(2))
                .verifyComplete();
    }

    @Test
    void emptyMarkerReturnsNoMemory() {
        when(deepSeekClient.chat(anyString(), anyString(), any()))
                .thenReturn(Mono.just("[empty]"));

        StepVerifier.create(extractor.extractAndSave("LIFE", "闲聊", "闲聊", "sess-2"))
                .assertNext(items -> assertThat(items).isEmpty())
                .verifyComplete();

        // 不应该调用 remember
        org.mockito.Mockito.verify(memoryAgent, org.mockito.Mockito.never())
                .remember(any(MemoryRequest.class));
    }

    @Test
    void llmFailureSilentlyDegrades() {
        when(deepSeekClient.chat(anyString(), anyString(), any()))
                .thenReturn(Mono.error(new RuntimeException("network down")));

        StepVerifier.create(extractor.extractAndSave("LIFE", "u", "a", "sess-3"))
                .assertNext(items -> assertThat(items).isEmpty())
                .verifyComplete();
    }

    @Test
    void malformedJsonReturnsEmpty() {
        when(deepSeekClient.chat(anyString(), anyString(), any()))
                .thenReturn(Mono.just("这不是 JSON，纯文本响应"));

        StepVerifier.create(extractor.extractAndSave("LIFE", "u", "a", "sess-4"))
                .assertNext(items -> assertThat(items).isEmpty())
                .verifyComplete();
    }

    @Test
    void stripsMarkdownCodeFence() {
        String llmOutput = "```json\n[{\"type\":\"EMOTION_TRACE\",\"title\":\"今天有点累\",\"content\":\"情绪低落\",\"importanceScore\":0.7,\"emotionalTone\":\"negative\"}]\n```";
        when(deepSeekClient.chat(anyString(), anyString(), any())).thenReturn(Mono.just(llmOutput));
        when(memoryAgent.remember(any(MemoryRequest.class))).thenAnswer(inv -> {
            MemoryRequest req = inv.getArgument(0);
            return new MemoryItem(1L, req.type(), req.title(), req.content(),
                    req.eventDate(), req.emotionalTone(),
                    req.importance() == null ? 5 : req.importance(),
                    req.sessionId(), null, LocalDateTime.now());
        });

        StepVerifier.create(extractor.extractAndSave("LIFE", "今天好累", "嗯嗯", "sess-5"))
                .assertNext(items -> {
                    assertThat(items).hasSize(1);
                    assertThat(items.get(0).type()).isEqualTo(MemoryType.EMOTION_TRACE);
                })
                .verifyComplete();
    }

    @Test
    void belowImportanceThresholdFilteredOut() {
        // importanceScore 0.5 < 阈值 0.6，应被过滤
        String llmOutput = """
                [{"type":"PREFERENCE","title":"次要偏好","content":"细节","importanceScore":0.5,"emotionalTone":"neutral"}]
                """;
        when(deepSeekClient.chat(anyString(), anyString(), any())).thenReturn(Mono.just(llmOutput));

        StepVerifier.create(extractor.extractAndSave("LIFE", "u", "a", "sess-6"))
                .assertNext(items -> assertThat(items).isEmpty())
                .verifyComplete();
    }

    @Test
    void unknownTypeFilteredOut() {
        // 未知 type 应当跳过这一条，其它合法条目继续
        String llmOutput = """
                [
                  {"type":"UNKNOWN_TYPE","title":"x","content":"y","importanceScore":0.9,"emotionalTone":"neutral"},
                  {"type":"GOAL","title":"学钢琴","content":"今年想学一首曲子","importanceScore":0.85,"emotionalTone":"positive"}
                ]
                """;
        when(deepSeekClient.chat(anyString(), anyString(), any())).thenReturn(Mono.just(llmOutput));
        when(memoryAgent.remember(any(MemoryRequest.class))).thenAnswer(inv -> {
            MemoryRequest req = inv.getArgument(0);
            return new MemoryItem(1L, req.type(), req.title(), req.content(),
                    req.eventDate(), req.emotionalTone(),
                    req.importance() == null ? 5 : req.importance(),
                    req.sessionId(), null, LocalDateTime.now());
        });

        StepVerifier.create(extractor.extractAndSave("LIFE", "u", "a", "sess-7"))
                .assertNext(items -> {
                    assertThat(items).hasSize(1);
                    assertThat(items.get(0).type()).isEqualTo(MemoryType.GOAL);
                })
                .verifyComplete();
    }
}