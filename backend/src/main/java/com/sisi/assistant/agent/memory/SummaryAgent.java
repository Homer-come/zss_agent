package com.sisi.assistant.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sisi.assistant.persistence.entity.ChatRecordEntity;
import com.sisi.assistant.persistence.entity.ConversationSummaryEntity;
import com.sisi.assistant.persistence.mapper.ChatRecordMapper;
import com.sisi.assistant.persistence.mapper.ConversationSummaryMapper;
import com.sisi.assistant.service.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话摘要 Agent：将多轮历史对话压缩为简洁摘要，替代原始消息作为长期上下文。
 * 两种触发方式：
 * 1. 滚动摘要（turn）：当会话内消息数达到阈值时，由 ConversationService 在对话后触发；
 * 2. 周期摘要（periodic）：由 PeriodicSummaryScheduler 定时对过去一段时间的对话生成摘要。
 *
 * 摘要存储在 conversation_summary 表中，PromptBuilder 组装上下文时会自动加载。
 */
@Service
public class SummaryAgent {

    private static final Logger log = LoggerFactory.getLogger(SummaryAgent.class);

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个对话摘要助手。将多轮对话历史压缩为一段简洁的摘要。
            要求：
            - 200-400字
            - 保留关键事实、情感变化、未完成的话题和用户的偏好信息
            - 使用第三人称叙述
            - 不要输出"对话摘要"等标题，直接输出摘要正文
            """;

    private final DeepSeekClient deepSeekClient;
    private final ChatRecordMapper chatRecordMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final int summaryTriggerMessages;
    private final int summaryMaxMessages;

    public SummaryAgent(DeepSeekClient deepSeekClient,
                       ChatRecordMapper chatRecordMapper,
                       ConversationSummaryMapper conversationSummaryMapper,
                       @Value("${sisi.assistant.summary-trigger-messages:10}") int summaryTriggerMessages,
                       @Value("${sisi.memory.summary-max-messages:20}") int summaryMaxMessages) {
        this.deepSeekClient = deepSeekClient;
        this.chatRecordMapper = chatRecordMapper;
        this.conversationSummaryMapper = conversationSummaryMapper;
        this.summaryTriggerMessages = summaryTriggerMessages;
        this.summaryMaxMessages = summaryMaxMessages;
    }

    /**
     * 检查当前会话是否需要触发滚动摘要。
     * 如果该会话的消息数达到 summaryTriggerMessages 的整数倍且最近一段尚未被摘要，则生成摘要。
     */
    public void checkAndSummarize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            // 查询该会话已处理的消息数
            int messageCount = countSessionMessages(sessionId);
            // 如果消息数未达到触发阈值，跳过
            if (messageCount < summaryTriggerMessages) {
                return;
            }
            // 查询上一条摘要覆盖到的消息位置
            int lastSummarizedEnd = getLastSummaryEnd(sessionId);
            int unSummarized = messageCount - lastSummarizedEnd;
            if (unSummarized < summaryTriggerMessages) {
                return;
            }
            // 异步生成摘要，不阻塞主流程
            generateTurnSummary(sessionId, lastSummarizedEnd, messageCount)
                    .subscribe(
                            summary -> log.info("会话 {} 滚动摘要生成成功 ({}-{})", sessionId, lastSummarizedEnd, messageCount),
                            error -> log.warn("会话 {} 滚动摘要生成失败", sessionId, error)
                    );
        } catch (Exception ex) {
            log.warn("检查摘要触发条件失败 (sessionId={})", sessionId, ex);
        }
    }

    /**
     * 生成滚动摘要并存储。
     */
    private reactor.core.publisher.Mono<String> generateTurnSummary(String sessionId, int rangeStart, int rangeEnd) {
        String dialogue = loadDialogueForSummary(sessionId, rangeStart, rangeEnd);
        if (dialogue.isBlank()) {
            return reactor.core.publisher.Mono.just("");
        }

        String prompt = "请将以下对话压缩为摘要：\n\n%s".formatted(dialogue);
        return deepSeekClient.chat(SUMMARY_SYSTEM_PROMPT, prompt, null)
                .flatMap(summary -> {
                    if (summary.isBlank()) {
                        return reactor.core.publisher.Mono.just("");
                    }
                    saveSummary(sessionId, "turn", summary, rangeStart, rangeEnd);
                    return reactor.core.publisher.Mono.just(summary);
                });
    }

    /**
     * 对过去一段时间的所有对话生成周期摘要（供 PeriodicSummaryScheduler 调用）。
     */
    public reactor.core.publisher.Mono<String> generatePeriodicSummary(LocalDateTime since) {
        String dialogue = loadDialogueSince(since);
        if (dialogue.isBlank()) {
            return reactor.core.publisher.Mono.just("");
        }

        String prompt = "请将以下近期对话压缩为一段周期性摘要：\n\n%s".formatted(dialogue);
        return deepSeekClient.chat(SUMMARY_SYSTEM_PROMPT, prompt, null)
                .flatMap(summary -> {
                    if (summary.isBlank()) {
                        return reactor.core.publisher.Mono.just("");
                    }
                    saveSummary("periodic-" + since.toLocalDate(), "periodic", summary, null, null);
                    return reactor.core.publisher.Mono.just(summary);
                });
    }

    /**
     * 加载指定会话的最新摘要文本，供 PromptBuilder 使用。
     */
    public String loadLatestSummary(String sessionId) {
        try {
            ConversationSummaryEntity summary = conversationSummaryMapper.selectOne(new LambdaQueryWrapper<ConversationSummaryEntity>()
                    .select(ConversationSummaryEntity::getContent)
                    .eq(ConversationSummaryEntity::getSessionId, sessionId)
                    .orderByDesc(ConversationSummaryEntity::getCreatedAt)
                    .last("LIMIT 1"));
            return summary == null ? "" : summary.getContent();
        } catch (DataAccessException ex) {
            return "";
        }
    }

    /**
     * 加载指定范围的原始对话文本，用于生成摘要。
     */
    private String loadDialogueForSummary(String sessionId, int rangeStart, int rangeEnd) {
        try {
            // 先查该 session 的所有聊天记录，按时间排序
            List<ChatRecordEntity> records = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecordEntity>()
                    .select(ChatRecordEntity::getUserMessage, ChatRecordEntity::getAssistantMessage)
                    .eq(ChatRecordEntity::getSessionId, sessionId)
                    .orderByAsc(ChatRecordEntity::getCreatedAt)
                    .last("LIMIT " + summaryMaxMessages));
            StringBuilder sb = new StringBuilder();
            for (ChatRecordEntity record : records) {
                String user = record.getUserMessage();
                String assistant = record.getAssistantMessage();
                if (user != null && !user.isBlank()) {
                    sb.append("用户: ").append(user).append('\n');
                }
                if (assistant != null && !assistant.isBlank()) {
                    sb.append("助手: ").append(assistant).append('\n');
                }
            }
            return sb.toString();
        } catch (DataAccessException ex) {
            log.warn("加载对话记录失败 (sessionId={})", sessionId, ex);
            return "";
        }
    }

    /**
     * 加载指定时间之后的所有对话。
     */
    private String loadDialogueSince(LocalDateTime since) {
        try {
            List<ChatRecordEntity> records = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecordEntity>()
                    .select(ChatRecordEntity::getUserMessage, ChatRecordEntity::getAssistantMessage)
                    .gt(ChatRecordEntity::getCreatedAt, since)
                    .orderByAsc(ChatRecordEntity::getCreatedAt)
                    .last("LIMIT " + summaryMaxMessages));
            StringBuilder sb = new StringBuilder();
            for (ChatRecordEntity record : records) {
                String user = record.getUserMessage();
                String assistant = record.getAssistantMessage();
                if (user != null && !user.isBlank()) {
                    sb.append("用户: ").append(user).append('\n');
                }
                if (assistant != null && !assistant.isBlank()) {
                    sb.append("助手: ").append(assistant).append('\n');
                }
            }
            return sb.toString();
        } catch (DataAccessException ex) {
            log.warn("加载近期对话记录失败", ex);
            return "";
        }
    }

    private int countSessionMessages(String sessionId) {
        try {
            return Math.toIntExact(chatRecordMapper.selectCount(new LambdaQueryWrapper<ChatRecordEntity>()
                    .eq(ChatRecordEntity::getSessionId, sessionId)));
        } catch (DataAccessException ex) {
            return 0;
        }
    }

    private int getLastSummaryEnd(String sessionId) {
        try {
            List<Object> ends = conversationSummaryMapper.selectObjs(Wrappers.<ConversationSummaryEntity>lambdaQuery()
                    .select(ConversationSummaryEntity::getMessageRangeEnd)
                    .eq(ConversationSummaryEntity::getSessionId, sessionId)
                    .eq(ConversationSummaryEntity::getSummaryType, "turn")
                    .isNotNull(ConversationSummaryEntity::getMessageRangeEnd)
                    .orderByDesc(ConversationSummaryEntity::getMessageRangeEnd)
                    .last("LIMIT 1"));
            if (ends.isEmpty() || ends.get(0) == null) {
                return 0;
            }
            return ((Number) ends.get(0)).intValue();
        } catch (DataAccessException ex) {
            return 0;
        }
    }

    private void saveSummary(String sessionId, String type, String content, Integer rangeStart, Integer rangeEnd) {
        try {
            ConversationSummaryEntity summary = new ConversationSummaryEntity();
            summary.setSessionId(sessionId);
            summary.setSummaryType(type);
            summary.setContent(content);
            summary.setMessageRangeStart(rangeStart);
            summary.setMessageRangeEnd(rangeEnd);
            summary.setCreatedAt(LocalDateTime.now());
            conversationSummaryMapper.insert(summary);
        } catch (DataAccessException ex) {
            log.warn("保存摘要失败 (sessionId={}, type={})", sessionId, type, ex);
        }
    }
}
