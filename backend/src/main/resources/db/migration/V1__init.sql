-- =============================================================
-- V1: 初始化数据库 schema（合并原 V1 + V2 内容）
-- 覆盖：长期记忆、聊天记录、用户画像、对话摘要、会话状态
-- 兼容：H2（MySQL mode）+ MySQL 8
-- =============================================================

-- 1. 长期记忆
CREATE TABLE IF NOT EXISTS memory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    memory_type VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    event_date DATE NULL,
    emotional_tone VARCHAR(40) NULL,
    importance INT NOT NULL DEFAULT 5,
    session_id VARCHAR(64) NULL,
    importance_score DOUBLE NULL,
    embedding_text TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memory_type(memory_type),
    INDEX idx_memory_event_date(event_date),
    INDEX idx_memory_session_id(session_id)
);

-- 2. 聊天记录
CREATE TABLE IF NOT EXISTS chat_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route VARCHAR(20) NOT NULL,
    session_id VARCHAR(64) NULL,
    user_message TEXT NOT NULL,
    assistant_message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_session_id(session_id),
    INDEX idx_chat_created_at(created_at)
);

-- 3. 用户画像（profile memory）
CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_key VARCHAR(60) NOT NULL,
    profile_value TEXT NOT NULL,
    confidence INT NOT NULL DEFAULT 5,
    source_memory_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_profile_key (profile_key)
);

-- 4. 对话摘要（滚动摘要 + 周期摘要）
CREATE TABLE IF NOT EXISTS conversation_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    summary_type VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    message_range_start INT NULL,
    message_range_end INT NULL,
    token_estimate INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_summary_session_id (session_id),
    INDEX idx_summary_created_at (created_at)
);

-- 5. 会话状态（per-session 任务上下文）
CREATE TABLE IF NOT EXISTS session_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    current_topic VARCHAR(200) NULL,
    emotional_state VARCHAR(40) NULL,
    task_context TEXT NULL,
    last_message_index INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_id (session_id)
);