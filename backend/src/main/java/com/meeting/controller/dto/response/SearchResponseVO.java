package com.meeting.controller.dto.response;

import java.util.List;

public record SearchResponseVO(
        String evidenceLevel,
        String strategyUsed,
        int totalCandidates,
        String queryUsed,
        List<SearchHitVO> results
) {}