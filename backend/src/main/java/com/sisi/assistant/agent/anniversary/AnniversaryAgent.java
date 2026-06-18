package com.sisi.assistant.agent.anniversary;

import com.sisi.assistant.agent.memory.MemoryAgent;
import com.sisi.assistant.common.dto.AnniversaryRequest;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryRequest;
import com.sisi.assistant.common.dto.MemoryType;
import com.sisi.assistant.service.prompt.AnniversaryPromptTemplates;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AnniversaryAgent {

    private static final List<Integer> REMINDER_DAYS = List.of(30, 7, 3, 1, 0);
    private final MemoryAgent memoryAgent;

    public AnniversaryAgent(MemoryAgent memoryAgent) {
        this.memoryAgent = memoryAgent;
    }

    /**
     * 保存纪念日。底层仍走 MemoryAgent，这样纪念日既能被定时提醒，也能被 LifeAgent 的 RAG 检索召回。
     */
    public MemoryItem save(AnniversaryRequest request) {
        return memoryAgent.remember(new MemoryRequest(
                MemoryType.ANNIVERSARY,
                request.title(),
                request.description() == null ? "思思的重要纪念日" : request.description(),
                request.date(),
                "warm",
                request.importance() == null ? 9 : request.importance()
        ));
    }

    /**
     * 找出今天需要提醒的纪念日。
     * REMINDER_DAYS 对应 30/7/3/1/当天提醒机制。
     */
    public List<MemoryItem> upcomingReminders(LocalDate today) {
        return memoryAgent.anniversaries().stream()
                .filter(item -> REMINDER_DAYS.contains(daysUntilNext(item.eventDate(), today)))
                .toList();
    }

    /**
     * 计算某个纪念日距离下一次发生还有几天。
     * 只比较月日，年份会自动滚动到今年或明年。
     */
    public int daysUntilNext(LocalDate date, LocalDate today) {
        LocalDate next = date.withYear(today.getYear());
        if (next.isBefore(today)) {
            next = next.plusYears(1);
        }
        return (int) ChronoUnit.DAYS.between(today, next);
    }

    /**
     * 把纪念日提醒转成可直接推送给用户的高情绪价值文案。
     */
    public String buildReminderMessage(MemoryItem item, LocalDate today) {
        int days = daysUntilNext(item.eventDate(), today);
        String dayText = days == 0 ? "就是今天" : "还有 " + days + " 天";
        return AnniversaryPromptTemplates.reminderMessage(item.title(), dayText);
    }
}
