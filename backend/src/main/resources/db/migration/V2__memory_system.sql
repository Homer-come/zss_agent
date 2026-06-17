-- =============================================================
-- V2: 全链路上下文压缩与分层记忆系统
-- 新增用户画像、对话摘要、会话状态表；
-- 扩展 memory_item 支持会话关联和LLM重要性评分。
-- =============================================================

-- 1. 用户画像表（Profile Memory）：从对话中自动沉淀的用户基本信息
CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_key VARCHAR(60) NOT NULL COMMENT '画像维度，如 职业、饮食偏好、过敏源',
    profile_value TEXT NOT NULL COMMENT '画像值',
    confidence INT NOT NULL DEFAULT 5 COMMENT '1-10 置信度，多次确认后递增',
    source_memory_id BIGINT NULL COMMENT '来源于哪条记忆',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_profile_key (profile_key)
);

-- 2. 对话摘要表：滚动摘要和周期摘要，替代无结构的原始消息溢出
CREATE TABLE IF NOT EXISTS conversation_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL COMMENT '关联的会话ID',
    summary_type VARCHAR(20) NOT NULL COMMENT 'turn=滚动摘要, session=会话摘要, periodic=周期摘要',
    content TEXT NOT NULL COMMENT '摘要文本',
    message_range_start INT NULL COMMENT '摘要覆盖的起始消息序号',
    message_range_end INT NULL COMMENT '摘要覆盖的结束消息序号',
    token_estimate INT NULL COMMENT '摘要的token估算值',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
);

-- 3. 会话状态表：服务端维护的 per-session 任务上下文
CREATE TABLE IF NOT EXISTS session_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL COMMENT '前端传来的session UUID',
    current_topic VARCHAR(200) NULL COMMENT '当前讨论的主题',
    emotional_state VARCHAR(40) NULL COMMENT '最近一轮的情绪判断',
    task_context TEXT NULL COMMENT '当前任务的简短描述',
    last_message_index INT NOT NULL DEFAULT 0 COMMENT '已处理到的消息序号，用于触发摘要',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_id (session_id)
);

-- 4. 扩展 memory_item 表：增加会话关联和LLM重要性评分
ALTER TABLE memory_item ADD COLUMN session_id VARCHAR(64) NULL COMMENT '关联会话ID' AFTER importance;
ALTER TABLE memory_item ADD COLUMN importance_score DOUBLE NULL COMMENT 'LLM评分 0.0-1.0' AFTER session_id;
ALTER TABLE memory_item ADD INDEX idx_session_id (session_id);
