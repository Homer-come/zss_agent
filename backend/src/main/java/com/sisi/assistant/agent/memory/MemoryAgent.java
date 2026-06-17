package com.sisi.assistant.agent.memory;

import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryRequest;
import com.sisi.assistant.common.dto.MemoryType;
import com.sisi.assistant.rag.MemorySearchResult;
import com.sisi.assistant.rag.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MemoryAgent {

    private final JdbcTemplate jdbcTemplate;
    private final RagService ragService;
    private final int topK;
    private final CopyOnWriteArrayList<MemoryItem> fallbackStore = new CopyOnWriteArrayList<>();

    public MemoryAgent(JdbcTemplate jdbcTemplate, RagService ragService, @Value("${sisi.memory.top-k:5}") int topK) {
        this.jdbcTemplate = jdbcTemplate;
        this.ragService = ragService;
        this.topK = topK;
    }

    /**
     * 写入一条长期记忆。
     * 设计上分两步：先写关系库保证可审计、可管理，再写 RAG 索引用于语义召回。
     */
    public MemoryItem remember(MemoryRequest request) {
        MemoryItem item = insert(request);
        ragService.index(item);
        return item;
    }

    /**
     * 批量写入多条长期记忆。用于 MemoryExtractor 一次性保存LLM提取的结构化记忆。
     * 每条记忆独立写库+建索引，单条失败不影响其余。
     */
    public List<MemoryItem> rememberAll(List<MemoryRequest> requests) {
        return requests.stream()
                .map(this::remember)
                .toList();
    }

    /**
     * 根据用户当前输入检索长期记忆。
     * 如果内存索引为空，就从 MySQL 全量加载一次并重新索引；这是本地开发时的轻量恢复策略。
     */
    public List<MemorySearchResult> retrieve(String query) {
        List<MemorySearchResult> results = ragService.search(query, topK);
        if (!results.isEmpty()) {
            return results;
        }
        loadAll().forEach(ragService::index);
        return ragService.search(query, topK);
    }

    /**
     * 从关系库加载全部记忆。
     * DataAccessException 会降级到 fallbackStore，避免数据库短暂不可用时整个生活模块崩掉。
     */
    public List<MemoryItem> loadAll() {
        try {
            return jdbcTemplate.query("SELECT * FROM memory_item ORDER BY importance DESC, created_at DESC",
                    (rs, rowNum) -> new MemoryItem(
                            rs.getLong("id"),
                            MemoryType.valueOf(rs.getString("memory_type")),
                            rs.getString("title"),
                            rs.getString("content"),
                            rs.getDate("event_date") == null ? null : rs.getDate("event_date").toLocalDate(),
                            rs.getString("emotional_tone"),
                            rs.getInt("importance"),
                            rs.getString("session_id"),
                            rs.getObject("importance_score") != null ? rs.getDouble("importance_score") : null,
                            rs.getTimestamp("created_at").toLocalDateTime()
                    ));
        } catch (DataAccessException ex) {
            return List.copyOf(fallbackStore);
        }
    }

    /**
     * 纪念日 Agent 只关心带日期的 ANNIVERSARY 类型记忆。
     */
    public List<MemoryItem> anniversaries() {
        return loadAll().stream()
                .filter(item -> item.type() == MemoryType.ANNIVERSARY)
                .filter(item -> item.eventDate() != null)
                .toList();
    }

    /**
     * 将 RAG 检索结果转成可直接放进 Prompt 的文本。
     * 这里不把 score 传给模型，避免模型把技术评分误当成用户事实。
     */
    public String formatForPrompt(List<MemorySearchResult> results) {
        if (results.isEmpty()) {
            return "暂无可用长期记忆。";
        }
        StringBuilder builder = new StringBuilder();
        for (MemorySearchResult result : results) {
            MemoryItem item = result.item();
            builder.append("- [").append(item.type()).append("] ")
                    .append(item.title()).append(": ")
                    .append(item.content());
            if (item.eventDate() != null) {
                builder.append("（日期：").append(item.eventDate()).append("）");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 关系库存储实现。
     * importance 做边界裁剪，防止前端或接口传入异常值污染排序逻辑。
     */
    private MemoryItem insert(MemoryRequest request) {
        int importance = request.importance() == null ? 5 : Math.max(1, Math.min(10, request.importance()));
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO memory_item(memory_type, title, content, event_date, emotional_tone, importance, session_id, embedding_text)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, request.type().name());
                ps.setString(2, request.title());
                ps.setString(3, request.content());
                ps.setDate(4, request.eventDate() == null ? null : Date.valueOf(request.eventDate()));
                ps.setString(5, request.emotionalTone());
                ps.setInt(6, importance);
                ps.setString(7, request.sessionId());
                ps.setString(8, "%s %s".formatted(request.title(), request.content()));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return new MemoryItem(key == null ? null : key.longValue(), request.type(), request.title(), request.content(),
                    request.eventDate(), request.emotionalTone(), importance, request.sessionId(),
                    null, LocalDateTime.now());
        } catch (DataAccessException ex) {
            // 兜底内存存储只保证本进程可用，适合开发/演示，不替代生产持久化。
            long id = fallbackStore.size() + 1L;
            MemoryItem item = new MemoryItem(id, request.type(), request.title(), request.content(),
                    request.eventDate(), request.emotionalTone(), importance, request.sessionId(),
                    null, LocalDateTime.now());
            fallbackStore.add(item);
            return item;
        }
    }

    /**
     * 保存完整一轮聊天记录。
     * 目前 chat_record 主要用于审计和后续扩展；如果要”自动沉淀长期记忆”，可以在这里接一个 Memory Extractor Agent。
     */
    public void saveChat(String route, String userMessage, String assistantMessage) {
        saveChat(route, userMessage, assistantMessage, null);
    }

    /**
     * 保存完整一轮聊天记录（带会话关联）。
     */
    public void saveChat(String route, String userMessage, String assistantMessage, String sessionId) {
        try {
            jdbcTemplate.update(“INSERT INTO chat_record(route, session_id, user_message, assistant_message, created_at) VALUES (?, ?, ?, ?, ?)”,
                    route, sessionId, userMessage, assistantMessage, Timestamp.valueOf(LocalDateTime.now()));
        } catch (DataAccessException ignored) {
            MemoryItem item = new MemoryItem((long) fallbackStore.size() + 1, MemoryType.CHAT,
                    “聊天记录”, userMessage + “\n” + assistantMessage, LocalDate.now(), “neutral”, 3,
                    sessionId, null, LocalDateTime.now());
            fallbackStore.add(item);
        }
    }

    /**
     * 给健康检查使用的简短状态信息。
     */
    public Map<String, Object> status() {
        return Map.of(
                "memoryCount", loadAll().size(),
                "rag", "in-memory fallback with Milvus-ready configuration"
        );
    }
}
