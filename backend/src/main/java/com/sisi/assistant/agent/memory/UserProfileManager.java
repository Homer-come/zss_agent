package com.sisi.assistant.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sisi.assistant.persistence.entity.UserProfileEntity;
import com.sisi.assistant.persistence.mapper.UserProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用户画像管理器：维护 user_profile 表，从对话中自动沉淀用户基本信息。
 *
 * 画像维度示例：
 * - 职业、所在地、年龄等基本信息
 * - 饮食偏好、过敏源、作息习惯等生活习惯
 * - 喜欢的颜色/品牌/音乐等审美偏好
 *
 * 画像数据在 PromptBuilder 中组装进 system prompt，让 LLM 每轮都能感知用户的基本特征。
 * 核心逻辑：相同 key 的新信息覆盖旧信息，confidence 取 max。
 */
@Service
public class UserProfileManager {

    private static final Logger log = LoggerFactory.getLogger(UserProfileManager.class);

    private final UserProfileMapper userProfileMapper;
    private final CopyOnWriteArrayList<Map.Entry<String, String>> fallbackStore = new CopyOnWriteArrayList<>();

    public UserProfileManager(UserProfileMapper userProfileMapper) {
        this.userProfileMapper = userProfileMapper;
    }

    /**
     * 加载全部画像条目，返回 key→value 的有序映射。
     */
    public Map<String, String> loadProfile() {
        try {
            List<UserProfileEntity> rows = userProfileMapper.selectList(new LambdaQueryWrapper<UserProfileEntity>()
                    .orderByDesc(UserProfileEntity::getConfidence));
            Map<String, String> profile = new LinkedHashMap<>();
            for (UserProfileEntity row : rows) {
                profile.put(row.getProfileKey(), row.getProfileValue());
            }
            return profile;
        } catch (DataAccessException ex) {
            Map<String, String> profile = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : fallbackStore) {
                profile.put(entry.getKey(), entry.getValue());
            }
            return profile;
        }
    }

    /**
     * 从 MemoryExtractor 提取的 PROFILE 类型记忆中更新画像。
     * 如果 key 已存在，更新 value 并提升 confidence；否则新增条目。
     */
    public void updateFromExtraction(String profileKey, String profileValue, Long sourceMemoryId) {
        if (profileKey == null || profileKey.isBlank() || profileValue == null || profileValue.isBlank()) {
            return;
        }
        try {
            UserProfileEntity current = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
                    .eq(UserProfileEntity::getProfileKey, profileKey));
            int updated = 0;
            if (current != null) {
                Integer confidence = current.getConfidence() == null ? 5 : current.getConfidence();
                updated = userProfileMapper.update(new LambdaUpdateWrapper<UserProfileEntity>()
                        .set(UserProfileEntity::getProfileValue, profileValue)
                        .set(UserProfileEntity::getConfidence, Math.min(confidence + 1, 10))
                        .set(UserProfileEntity::getSourceMemoryId, sourceMemoryId)
                        .set(UserProfileEntity::getUpdatedAt, LocalDateTime.now())
                        .eq(UserProfileEntity::getProfileKey, profileKey));
            }
            if (updated == 0) {
                UserProfileEntity entity = new UserProfileEntity();
                entity.setProfileKey(profileKey);
                entity.setProfileValue(profileValue);
                entity.setConfidence(5);
                entity.setSourceMemoryId(sourceMemoryId);
                entity.setUpdatedAt(LocalDateTime.now());
                userProfileMapper.insert(entity);
                log.info("新增用户画像: {} = {}", profileKey, profileValue);
            } else {
                log.debug("更新用户画像: {} = {} (confidence+1)", profileKey, profileValue);
            }
        } catch (DataAccessException ex) {
            // 更新内存兜底
            fallbackStore.removeIf(e -> e.getKey().equals(profileKey));
            fallbackStore.add(Map.entry(profileKey, profileValue));
            log.warn("用户画像持久化失败，已降级到内存: {} = {}", profileKey, profileValue, ex);
        }
    }

    /**
     * 将画像格式化为可放入 Prompt 的自然语言文本。
     * 不暴露数据库字段名，只用用户可读的描述。
     */
    public String formatForPrompt() {
        Map<String, String> profile = loadProfile();
        if (profile.isEmpty()) {
            return "暂无用户画像信息。";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : profile.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }
}
