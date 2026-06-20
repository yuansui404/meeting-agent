package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileProcessingService {

    private final MeetingMinutesRepository meetingRepository;

    @Value("${file.upload-dir}")
    private String uploadDir;

    public FileProcessingService(MeetingMinutesRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    public MeetingMinutes uploadFile(MultipartFile file) throws IOException {
        // 验证文件类型
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".mp4")) {
            throw new IllegalArgumentException("Only MP4 files are supported");
        }

        // 创建上传目录
        Path uploadPath = Paths.get(uploadDir);
        Files.createDirectories(uploadPath);

        // 保存文件
        String savedFilename = UUID.randomUUID() + "_" + filename;
        Path filePath = uploadPath.resolve(savedFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        // 创建会议记录
        MeetingMinutes meeting = new MeetingMinutes();
        meeting.setTitle(filename);
        meeting.setFilePath(filePath.toString());
        meeting.setFileSize(file.getSize());
        meeting.setStatus("processing");

        return meetingRepository.save(meeting);
    }

    @Async
    public void extractAudio(Long meetingId) {
        // TODO: 使用 FFmpeg 提取音频
        // 这部分在集成 FFmpeg 后实现
    }

    public Path getAudioPath(Path videoPath) {
        String videoName = videoPath.getFileName().toString();
        String audioName = videoName.replace(".mp4", ".wav");
        return videoPath.getParent().resolve(audioName);
    }

    public void cleanupFile(Long meetingId) {
        meetingRepository.findById(meetingId).ifPresent(meeting -> {
            try {
                Path filePath = Paths.get(meeting.getFilePath());
                Files.deleteIfExists(filePath);

                Path audioPath = getAudioPath(filePath);
                Files.deleteIfExists(audioPath);
            } catch (IOException e) {
                // 忽略清理异常
            }
        });
    }
}
