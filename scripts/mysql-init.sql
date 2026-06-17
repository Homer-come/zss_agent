CREATE DATABASE IF NOT EXISTS zss_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE zss_agent;

CREATE TABLE IF NOT EXISTS memory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    memory_type VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    event_date DATE NULL,
    emotional_tone VARCHAR(40) NULL,
    importance INT NOT NULL DEFAULT 5,
    embedding_text TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_memory_type(memory_type),
    INDEX idx_memory_event_date(event_date)
);

CREATE TABLE IF NOT EXISTS chat_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route VARCHAR(20) NOT NULL,
    user_message TEXT NOT NULL,
    assistant_message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 用户画像表：从对话中自动沉淀的用户基本信息
CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_key VARCHAR(60) NOT NULL,
    profile_value TEXT NOT NULL,
    confidence INT NOT NULL DEFAULT 5,
    source_memory_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_profile_key (profile_key)
);

-- 对话摘要表：滚动摘要和周期摘要
CREATE TABLE IF NOT EXISTS conversation_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    summary_type VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    message_range_start INT NULL,
    message_range_end INT NULL,
    token_estimate INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
);

-- 会话状态表：服务端维护的 per-session 任务上下文
CREATE TABLE IF NOT EXISTS session_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    current_topic VARCHAR(200) NULL,
    emotional_state VARCHAR(40) NULL,
    task_context TEXT NULL,
    last_message_index INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_id (session_id)
);

-- 扩展 memory_item：增加会话关联和LLM重要性评分
ALTER TABLE memory_item ADD COLUMN session_id VARCHAR(64) NULL AFTER importance;
ALTER TABLE memory_item ADD COLUMN importance_score DOUBLE NULL AFTER session_id;
ALTER TABLE memory_item ADD INDEX idx_session_id (session_id);

-- 扩展 chat_record：增加会话关联
ALTER TABLE chat_record ADD COLUMN session_id VARCHAR(64) NULL AFTER route;
ALTER TABLE chat_record ADD INDEX idx_session_id (session_id);
