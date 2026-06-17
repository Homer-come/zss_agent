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
