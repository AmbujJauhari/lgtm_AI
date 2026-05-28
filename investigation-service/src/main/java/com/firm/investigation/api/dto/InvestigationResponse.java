package com.firm.investigation.api.dto;

public record InvestigationResponse(
    String dashboardUrl,
    String dashboardTitle,
    boolean fallback
) {}
