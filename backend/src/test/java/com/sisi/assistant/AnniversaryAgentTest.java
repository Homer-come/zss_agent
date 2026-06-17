package com.sisi.assistant;

import com.sisi.assistant.agent.anniversary.AnniversaryAgent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
class AnniversaryAgentTest {

    @Test
    void calculatesNextReminderAcrossYearBoundary() {
        AnniversaryAgent agent = new AnniversaryAgent(null);

        int days = agent.daysUntilNext(LocalDate.of(2024, 1, 3), LocalDate.of(2023, 12, 27));

        assertThat(days).isEqualTo(7);
    }
}
