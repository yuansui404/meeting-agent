package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StyleLearningService {

    private static final Logger log = LoggerFactory.getLogger(StyleLearningService.class);
    private static final int MAX_EXAMPLES = 5;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final VectorizationService vectorizationService;
    private final MeetingMinutesRepository meetingRepository;

    public StyleLearningService(VectorizationService vectorizationService,
                                MeetingMinutesRepository meetingRepository) {
        this.vectorizationService = vectorizationService;
        this.meetingRepository = meetingRepository;
    }

    /**
     * Retrieve style examples from knowledge base, weighted by priority_score.
     *
     * @param query           the user's rewrite query or source file content
     * @param excludeFileIds  meetings to exclude (previously used style refs)
     * @return formatted style examples prompt section, or empty string if none
     */
    public String buildStyleExamples(String query, List<Long> excludeFileIds) {
        List<VectorizationService.ScoredVector> examples =
                vectorizationService.searchStyleExamples(query, MAX_EXAMPLES, excludeFileIds);

        if (examples.isEmpty()) return "";
        if (examples.get(0).similarity() < SIMILARITY_THRESHOLD) return "";

        // Full threshold filter
        List<VectorizationService.ScoredVector> relevant = examples.stream()
                .filter(v -> v.similarity() >= SIMILARITY_THRESHOLD)
                .toList();
        if (relevant.isEmpty()) return "";

        // Build title cache
        java.util.Map<Long, String> titleCache = new java.util.HashMap<>();
        for (VectorizationService.ScoredVector v : relevant) {
            titleCache.computeIfAbsent(v.meetingId(),
                    id -> meetingRepository.findById(id)
                            .map(MeetingMinutes::getTitle)
                            .orElse("未知文档"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("请参考以下历史优秀纪要的写作风格和行文结构进行改写。\n\n");
        sb.append("风格参考示例：\n");

        for (int i = 0; i < relevant.size(); i++) {
            VectorizationService.ScoredVector v = relevant.get(i);
            String source = titleCache.get(v.meetingId());
            if (i > 0) sb.append("\n---\n");
            sb.append("【示例 ").append(i + 1).append(" 来自：").append(source).append("】\n");
            sb.append(v.content());
        }

        sb.append("\n\n请参考以上示例的风格进行改写，保持专业性和一致性。");
        return sb.toString();
    }
}
