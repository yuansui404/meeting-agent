package com.meeting.retrieval.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Citation {
    @JsonProperty("source_id")
    private int sourceId;
    @JsonProperty("file_name")
    private String fileName;
    private String content;
    @JsonProperty("chunk_index")
    private Integer chunkIndex;
}
