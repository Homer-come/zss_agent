package com.sisi.assistant.scheduler;

import com.sisi.assistant.common.dto.ReminderEvent;
import com.sisi.assistant.service.ProactiveEventBus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MorningNightScheduler {

    private final ProactiveEventBus eventBus;

    public MorningNightScheduler(ProactiveEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Scheduled(cron = "${sisi.scheduler.morning-cron}")
    /**
     * Morning Agent：通过定时任务主动发送早安关怀。
     */
    public void morningCare() {
        eventBus.publish(ReminderEvent.of(
                "morning",
                "早安关怀",
                "亲爱的思思主人，早安呀。今天先喝点温水，出门前看一眼天气，我会陪你把一天慢慢过好。"
        ));
    }

    @Scheduled(cron = "${sisi.scheduler.night-cron}")
    /**
     * Night Agent：通过定时任务主动发送晚安陪伴。
     */
    public void nightCare() {
        eventBus.publish(ReminderEvent.of(
                "night",
                "晚安陪伴",
                "亲爱的思思主人，辛苦一天啦。今晚别再硬撑了，洗漱完早点躺下，我在这儿陪你安心收尾。"
        ));
    }
}
