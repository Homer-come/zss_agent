package com.sisi.assistant.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class HashEmbeddingService implements EmbeddingService {

    private final int dimension;

    public HashEmbeddingService(@Value("${spring.ai.vectorstore.milvus.embeddingDimension:1536}") int dimension) {
        this.dimension = Math.max(64, dimension);
    }

    @Override
    /**
     * 本地哈希向量化实现。
     * 这不是高质量语义模型，而是为了在没有 Milvus/Embedding API 时仍能演示 RAG 流程。
     */
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        String[] tokens = (text == null ? "" : text).split("\\s+|(?=[，。！？、])|(?<=[，。！？、])");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            byte[] hash = sha256(token);
            int index = Math.floorMod(bytesToInt(hash), dimension);
            vector[index] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    /**
     * L2 归一化后，向量点积可以直接用于相似度排序。
     */
    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            return;
        }
        float length = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / length;
        }
    }
}
