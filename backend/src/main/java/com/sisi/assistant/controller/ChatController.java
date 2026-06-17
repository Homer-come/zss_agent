package com.sisi.assistant.controller;

import com.sisi.assistant.agent.anniversary.AnniversaryAgent;
import com.sisi.assistant.agent.memory.MemoryAgent;
import com.sisi.assistant.common.dto.AnniversaryRequest;
import com.sisi.assistant.common.dto.ChatChunk;
import com.sisi.assistant.common.dto.ChatRequest;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryRequest;
import com.sisi.assistant.common.dto.ReminderEvent;
import com.sisi.assistant.service.ConversationService;
import com.sisi.assistant.service.ProactiveEventBus;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ConversationService conversationService;
    private final MemoryAgent memoryAgent;
    private final AnniversaryAgent anniversaryAgent;
    private final ProactiveEventBus eventBus;

    public ChatController(ConversationService conversationService,
                          MemoryAgent memoryAgent,
                          AnniversaryAgent anniversaryAgent,
                          ProactiveEventBus eventBus) {
        this.conversationService = conversationService;
        this.memoryAgent = memoryAgent;
        this.anniversaryAgent = anniversaryAgent;
        this.eventBus = eventBus;
    }

    /**
     * 主对话入口：前端通过 POST 建立一次 SSE 流式响应。
     * 调用链：Controller -> ConversationService -> IntentRouter -> WorkAgent/LifeAgent -> DeepSeekClient。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunk>> stream(@Valid @RequestBody ChatRequest request) {
        return conversationService.stream(request)
                .map(chunk -> ServerSentEvent.builder(chunk).event(chunk.type()).build());
    }

    /**
     * 主动陪伴通道：这是“系统主动找用户”的服务端出口。
     * Scheduler 会把早安、晚安、纪念日提醒写入 eventBus，这里再推给浏览器。
     */
    @GetMapping(value = "/companion/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ReminderEvent>> companionEvents() {
        Flux<ReminderEvent> heartbeat = Flux.interval(Duration.ofSeconds(25))
                .map(tick -> ReminderEvent.of("heartbeat", "连接正常", "我在，主动陪伴通道保持在线。"));
        return Flux.merge(eventBus.stream(), heartbeat)
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }

    /**
     * 保存长期记忆。记忆会先落关系库，再进入 RAG 索引，供 LifeAgent 后续检索。
     */
    @PostMapping("/memory")
    public MemoryItem remember(@Valid @RequestBody MemoryRequest request) {
        return memoryAgent.remember(request);
    }

    @GetMapping("/memory")
    public List<MemoryItem> memories() {
        return memoryAgent.loadAll();
    }

    /**
     * 纪念日是长期记忆的一种特殊类型，统一交给 AnniversaryAgent 做日期和提醒语义管理。
     */
    @PostMapping("/anniversaries")
    public MemoryItem anniversary(@Valid @RequestBody AnniversaryRequest request) {
        return anniversaryAgent.save(request);
    }

    /**
     * 轻量 readiness 接口。除了 UP，也暴露降级策略，方便开发时判断 RAG/SSE 是否在线。
     */
    @GetMapping("/health/ready")
    public Map<String, Object> ready() {
        return Map.of(
                "status", "UP",
                "memory", memoryAgent.status(),
                "sse", "enabled",
                "degradePolicy", "model/milvus/firecrawl unavailable -> graceful fallback"
        );
    }
}
