package com.sisi.assistant.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sisi.assistant.common.dto.MemoryItem;
import com.sisi.assistant.common.dto.MemoryRequest;
import com.sisi.assistant.common.dto.MemoryType;
import com.sisi.assistant.persistence.entity.ChatRecordEntity;
import com.sisi.assistant.persistence.entity.MemoryItemEntity;
import com.sisi.assistant.persistence.mapper.ChatRecordMapper;
import com.sisi.assistant.persistence.mapper.MemoryItemMapper;
import com.sisi.assistant.rag.MemorySearchResult;
import com.sisi.assistant.rag.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MemoryAgent {

    private final MemoryItemMapper memoryItemMapper;
    private final ChatRecordMapper chatRecordMapper;
    private final RagService ragService;
    private final int topK;
    private final CopyOnWriteArrayList<MemoryItem> fallbackStore = new CopyOnWriteArrayList<>();

    public MemoryAgent(MemoryItemMapper memoryItemMapper,
                       ChatRecordMapper chatRecordMapper,
                       RagService ragService,
                       @Value("${sisi.memory.top-k:5}") int topK) {
        this.memoryItemMapper = memoryItemMapper;
        this.chatRecordMapper = chatRecordMapper;
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
            return memoryItemMapper.selectList(new LambdaQueryWrapper<MemoryItemEntity>()
                            .orderByDesc(MemoryItemEntity::getImportance)
                            .orderByDesc(MemoryItemEntity::getCreatedAt))
                    .stream()
                    .map(this::toDto)
                    .toList();
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
            MemoryItemEntity entity = new MemoryItemEntity();
            entity.setMemoryType(request.type().name());
            entity.setTitle(request.title());
            entity.setContent(request.content());
            entity.setEventDate(request.eventDate());
            entity.setEmotionalTone(request.emotionalTone());
            entity.setImportance(importance);
            entity.setSessionId(request.sessionId());
            entity.setEmbeddingText("%s %s".formatted(request.title(), request.content()));
            entity.setCreatedAt(LocalDateTime.now());
            memoryItemMapper.insert(entity);
            return new MemoryItem(entity.getId(), request.type(), request.title(), request.content(),
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
            ChatRecordEntity record = new ChatRecordEntity();
            record.setRoute(route);
            record.setSessionId(sessionId);
            record.setUserMessage(userMessage);
            record.setAssistantMessage(assistantMessage);
            record.setCreatedAt(LocalDateTime.now());
            chatRecordMapper.insert(record);
        } catch (DataAccessException ignored) {
            MemoryItem item = new MemoryItem((long) fallbackStore.size() + 1, MemoryType.CHAT,
                    "聊天记录", userMessage + "\n" + assistantMessage, LocalDate.now(), "neutral", 3,
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

    private MemoryItem toDto(MemoryItemEntity entity) {
        return new MemoryItem(
                entity.getId(),
                MemoryType.valueOf(entity.getMemoryType()),
                entity.getTitle(),
                entity.getContent(),
                entity.getEventDate(),
                entity.getEmotionalTone(),
                entity.getImportance() == null ? 5 : entity.getImportance(),
                entity.getSessionId(),
                entity.getImportanceScore(),
                entity.getCreatedAt()
        );
    }
}
