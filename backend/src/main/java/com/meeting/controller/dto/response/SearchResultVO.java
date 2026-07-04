package com.meeting.controller.dto.response;

import java.util.List;

public record SearchResultVO(
        String query,
        String evidenceLevel,
        List<?> results,
        List<?> citations
) {}
