package com.meeting.document.service;

import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;

import java.util.List;

public interface ChunkStrategy {
    List<ChunkSegment> chunk(String text, RagProperties.Chunk config);
}
