package com.sisi.assistant.rag;

import com.sisi.assistant.common.dto.MemoryItem;

import java.util.List;

public interface RagService {

    void index(MemoryItem item);

    List<MemorySearchResult> search(String query, int topK);
}
