package com.sisi.assistant.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record AnniversaryRequest(
        @NotBlank String title,
        @NotNull LocalDate date,
        String description,
        Integer importance
) {
}
