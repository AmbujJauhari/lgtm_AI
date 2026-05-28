package com.firm.investigation.api.dto;

import jakarta.validation.constraints.NotBlank;

public record InvestigationRequest(
    @NotBlank String logLine,
    @NotBlank String service,
    @NotBlank String appcode,
    @NotBlank String timestamp
) {}
