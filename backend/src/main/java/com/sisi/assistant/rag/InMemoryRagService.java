package com.sisi.assistant.rag;

import com.sisi.assistant.common.dto.MemoryItem;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class InMemoryRagService implements RagService {

    private final EmbeddingService embeddingService;
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    public InMemoryRagService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    /**
     * 将 MemoryItem 写入本地向量索引。
     * 这里用 CopyOnWriteArrayList 是为了让读多写少的检索场景足够简单；生产环境可替换为 Milvus。
     */
    public void index(MemoryItem item) {
        entries.removeIf(entry -> entry.item.id() != null && entry.item.id().equals(item.id()));
        entries.add(new Entry(item, embeddingService.embed(item.searchableText())));
    }

    @Override
    /**
     * 语义检索：query 和每条记忆都转成向量后计算余弦相似度，按分数倒序返回 topK。
     */
    public List<MemorySearchResult> search(String query, int topK) {
        float[] queryVector = embeddingService.embed(query);
        return entries.stream()
                .map(entry -> new MemorySearchResult(entry.item, cosine(queryVector, entry.vector)))
                .sorted(Comparator.comparingDouble(MemorySearchResult::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    /**
     * 因为 HashEmbeddingService 已做归一化，这里点积就等价于余弦相似度。
     */
    private double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private record Entry(MemoryItem item, float[] vector) {
    }
}
