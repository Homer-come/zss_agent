package com.sisi.assistant;

import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.router.IntentRouter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void routesPptRequestsToWork() {
        assertThat(router.route("帮我把这篇长文总结成 PPT 大纲").route()).isEqualTo(AgentRoute.WORK);
    }

    @Test
    void routesCareRequestsToLife() {
        assertThat(router.route("今天好累，想喝奶茶").route()).isEqualTo(AgentRoute.LIFE);
    }
}
