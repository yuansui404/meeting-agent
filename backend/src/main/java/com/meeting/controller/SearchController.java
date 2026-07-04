package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.response.SearchResultVO;
import com.meeting.retrieval.service.HybridSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SearchController {

    private final HybridSearchService hybridSearchService;

    @GetMapping("/search")
    public ApiResponse<SearchResultVO> search(
            @RequestParam String query,
            @RequestParam(required = false) String timeRange) {

        var result = hybridSearchService.search(query, timeRange);
        return ApiResponse.ok(new SearchResultVO(
                query,
                result.evidenceLevel(),
                result.chunks(),
                result.citations()
        ));
    }
}
