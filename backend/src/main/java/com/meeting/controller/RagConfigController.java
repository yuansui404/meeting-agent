package com.meeting.controller;

import com.meeting.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagConfigController {

    private final RagProperties ragProperties;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chunk", toMap(
                "strategy", ragProperties.getChunk().getStrategy(),
                "size", ragProperties.getChunk().getSize(),
                "overlap", ragProperties.getChunk().getOverlap()
        ));
        config.put("retrieval", toMap(
                "vectorTopk", ragProperties.getRetrieval().getVectorTopk(),
                "ftsTopk", ragProperties.getRetrieval().getFtsTopk(),
                "rrfK", ragProperties.getRetrieval().getRrfK(),
                "rerankEnabled", ragProperties.getRetrieval().isRerankEnabled(),
                "rerankTopk", ragProperties.getRetrieval().getRerankTopk()
        ));
        config.put("evidence", toMap(
                "threshold", ragProperties.getEvidence().getThreshold(),
                "adaptive", ragProperties.getEvidence().isAdaptive(),
                "adaptiveStep", ragProperties.getEvidence().getAdaptiveStep(),
                "adaptiveMax", ragProperties.getEvidence().getAdaptiveMax()
        ));
        config.put("timeDecay", toMap(
                "enabled", ragProperties.getTimeDecay().isEnabled(),
                "recentDays", ragProperties.getTimeDecay().getRecentDays(),
                "recentWeight", ragProperties.getTimeDecay().getRecentWeight(),
                "normalWeight", ragProperties.getTimeDecay().getNormalWeight(),
                "oldWeight", ragProperties.getTimeDecay().getOldWeight(),
                "archiveWeight", ragProperties.getTimeDecay().getArchiveWeight()
        ));
        return config;
    }

    private static Map<String, Object> toMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}