package com.sisi.assistant.service;

import com.sisi.assistant.common.dto.ReminderEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class ProactiveEventBus {

    /**
     * 多播 Sink：后端内部发布一次事件，所有已连接的 SSE 客户端都能收到。
     * 适合主动陪伴这类“服务器主动推送”的场景。
     */
    private final Sinks.Many<ReminderEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Scheduler 或其他 Agent 调用 publish，把主动消息投递到事件总线。
     */
    public void publish(ReminderEvent event) {
        sink.tryEmitNext(event);
    }

    /**
     * Controller 订阅这个 Flux，并转换成 ServerSentEvent 推给浏览器。
     */
    public Flux<ReminderEvent> stream() {
        return sink.asFlux();
    }
}
