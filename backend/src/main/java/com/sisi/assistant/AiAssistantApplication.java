package com.sisi.assistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@MapperScan("com.sisi.assistant.persistence.mapper")
@SpringBootApplication
public class AiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}
