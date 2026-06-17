package com.sisi.assistant.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态管理器：服务端维护每个 session 的当前任务上下文。
 *
 * 存储内容：
 * - currentTopic: 当前讨论的主题（如"晚餐推荐"、"PPT修改"）
 * - emotionalState: 最近一轮的情绪判断（如"happy"、"tired"）
 * - taskContext: 当前任务的简短描述
 * - lastMessageIndex: 已处理到的消息序号，用于触发滚动摘要
 *
 * 设计原则：
 * - 不追求精确性，是给 LLM 的辅助信息；
 * - 如果 session 不存在则自动创建默认状态；
 * - 使用 ConcurrentHashMap 作为内存缓存，避免每次都查数据库。
 */
@Service
public class SessionStateManager {

    private static final Logger log = LoggerFactory.getLogger(SessionStateManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, SessionState> cache = new ConcurrentHashMap<>();

    public SessionStateManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取指定会话的状态。如果不存在则返回默认状态并持久化。
     */
    public SessionState getState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionState.defaultState();
        }
        return cache.computeIfAbsent(sessionId, this::loadOrCreate);
    }

    /**
     * 更新会话状态。每次对话结束后调用，记录当前主题和情绪。
     * lastMessageIndex 自增，用于 SummaryAgent 判断是否触发摘要。
     */
    public void updateState(String sessionId, String route) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SessionState state = getState(sessionId);
        state.lastMessageIndex++;
        state.currentRoute = route;
        state.updatedAt = System.currentTimeMillis();

        try {
            jdbcTemplate.update("""
                    INSERT INTO session_state(session_id, current_topic, emotional_state, task_context, last_message_index, updated_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE
                        current_topic = VALUES(current_topic),
                        emotional_state = VALUES(emotional_state),
                        task_context = VALUES(task_context),
                        last_message_index = VALUES(last_message_index),
                        updated_at = NOW()
                    """,
                    sessionId, state.currentTopic, state.emotionalState,
                    state.taskContext, state.lastMessageIndex);
        } catch (DataAccessException ex) {
            log.debug("会话状态持久化失败，内存缓存仍然有效 (sessionId={})", sessionId);
        }
    }

    /**
     * 用 LLM 提取的情绪信息更新会话状态。
     */
    public void updateEmotion(String sessionId, String emotion) {
        if (sessionId == null || sessionId.isBlank() || emotion == null || emotion.isBlank()) {
            return;
        }
        SessionState state = getState(sessionId);
        state.emotionalState = emotion;
        try {
            jdbcTemplate.update(
                    "UPDATE session_state SET emotional_state = ?, updated_at = NOW() WHERE session_id = ?",
                    emotion, sessionId);
        } catch (DataAccessException ignored) {
        }
    }

    /**
     * 将会话状态格式化为 Prompt 可读文本。
     */
    public String formatForPrompt(String sessionId) {
        SessionState state = getState(sessionId);
        StringBuilder builder = new StringBuilder();
        if (state.currentTopic != null && !state.currentTopic.isBlank()) {
            builder.append("当前话题: ").append(state.currentTopic).append('\n');
        }
        if (state.emotionalState != null && !state.emotionalState.isBlank()) {
            builder.append("情绪状态: ").append(state.emotionalState).append('\n');
        }
        if (state.taskContext != null && !state.taskContext.isBlank()) {
            builder.append("任务上下文: ").append(state.taskContext).append('\n');
        }
        return builder.isEmpty() ? "暂无当前任务状态。" : builder.toString();
    }

    private SessionState loadOrCreate(String sessionId) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT * FROM session_state WHERE session_id = ?", sessionId);
            return new SessionState(
                    (String) row.get("current_topic"),
                    (String) row.get("emotional_state"),
                    (String) row.get("task_context"),
                    ((Number) row.get("last_message_index")).intValue()
            );
        } catch (DataAccessException ex) {
            // 表不存在或无记录，返回默认状态
            return SessionState.defaultState();
        }
    }

    /**
     * 会话状态的内存表示。
     */
    public static class SessionState {
        public String currentTopic;
        public String emotionalState;
        public String taskContext;
        public int lastMessageIndex;
        public long updatedAt;

        public SessionState(String currentTopic, String emotionalState,
                           String taskContext, int lastMessageIndex) {
            this.currentTopic = currentTopic;
            this.emotionalState = emotionalState;
            this.taskContext = taskContext;
            this.lastMessageIndex = lastMessageIndex;
            this.updatedAt = System.currentTimeMillis();
        }

        public static SessionState defaultState() {
            return new SessionState(null, null, null, 0);
        }
    }
}
