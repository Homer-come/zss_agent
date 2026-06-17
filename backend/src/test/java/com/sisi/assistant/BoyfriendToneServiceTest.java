package com.sisi.assistant;

import com.sisi.assistant.agent.boyfriend.BoyfriendToneService;
import com.sisi.assistant.common.config.AssistantProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoyfriendToneServiceTest {

    @Test
    void enforcesLifeOpening() {
        AssistantProperties properties = new AssistantProperties();
        BoyfriendToneService service = new BoyfriendToneService(properties);

        assertThat(service.enforceLifeOpening("今天先吃点热的。")).startsWith("亲爱的思思主人");
    }
}
