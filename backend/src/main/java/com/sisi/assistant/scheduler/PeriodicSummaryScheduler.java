package com.sisi.assistant.scheduler;

import com.sisi.assistant.agent.memory.SummaryAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 周期摘要定时任务：每天凌晨对过去一天的对话生成全局摘要。
 * 周期摘要用于跨 session 的长期上下文，让 LLM 即使在新会话中也能感知近期的对话脉络。
 */
@Component
public class PeriodicSummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSummaryScheduler.class);

    private final SummaryAgent summaryAgent;

    public PeriodicSummaryScheduler(SummaryAgent summaryAgent) {
        this.summaryAgent = summaryAgent;
    }

    /**
     * 每天凌晨3点，对过去24小时的对话生成周期摘要。
     * 使用 fixedDelay 避免在前一次未完成时重复触发。
     */
    @Scheduled(cron = "${sisi.scheduler.periodic-summary-cron:0 0 3 * * *}")
    public void dailySummary() {
        log.info("开始生成每日周期摘要...");
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        summaryAgent.generatePeriodicSummary(since)
                .subscribe(
                        summary -> {
                            if (summary != null && !summary.isBlank()) {
                                log.info("每日周期摘要生成成功，长度: {} 字", summary.length());
                            } else {
                                log.info("过去24小时无对话记录，跳过周期摘要");
                            }
                        },
                        error -> log.warn("每日周期摘要生成失败", error)
                );
    }
}
