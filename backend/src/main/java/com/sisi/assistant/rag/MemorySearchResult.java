package com.sisi.assistant.rag;

import com.sisi.assistant.common.dto.MemoryItem;

public record MemorySearchResult(
        MemoryItem item,
        double score
) {
}
