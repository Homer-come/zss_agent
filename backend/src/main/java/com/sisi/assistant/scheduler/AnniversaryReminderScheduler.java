package com.sisi.assistant.scheduler;

import com.sisi.assistant.agent.anniversary.AnniversaryAgent;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.ReminderEvent;
import com.sisi.assistant.service.ProactiveEventBus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class AnniversaryReminderScheduler {

    private final AnniversaryAgent anniversaryAgent;
    private final ProactiveEventBus eventBus;

    public AnniversaryReminderScheduler(AnniversaryAgent anniversaryAgent, ProactiveEventBus eventBus) {
        this.anniversaryAgent = anniversaryAgent;
        this.eventBus = eventBus;
    }

    @Scheduled(cron = "0 0 9 * * *")
    /**
     * 每天 9 点扫描纪念日，命中提醒窗口后通过 ProactiveEventBus 推给前端。
     */
    public void remind() {
        LocalDate today = LocalDate.now();
        for (MemoryItem item : anniversaryAgent.upcomingReminders(today)) {
            eventBus.publish(ReminderEvent.of(
                    "anniversary",
                    item.title(),
                    anniversaryAgent.buildReminderMessage(item, today)
            ));
        }
    }
}
