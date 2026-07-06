package com.meeting.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FileMetadata(
        /** 文件唯一标识 */
        String fileId,
        /** 原始文件名 */
        String fileName,
        /** 文件存储路径 */
        String filePath,
        /** 文件扩展名（如 mp4、pdf） */
        String ext,
        /** 文件大小（字节） */
        Long fileSize,
        /** 处理状态（pending / processing / completed / failed） */
        String status
) {}
