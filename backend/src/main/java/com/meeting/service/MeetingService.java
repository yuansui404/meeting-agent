package com.meeting.service;

import com.meeting.common.BusinessException;
import com.meeting.controller.dto.response.MeetingDetailVO;
import com.meeting.controller.dto.response.MeetingListVO;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.meeting.repository.MeetingVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingMinutesRepository meetingRepository;
    private final MeetingVectorRepository vectorRepository;

    public MeetingDetailVO getById(Long id) {
        MeetingMinutes m = meetingRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("会议不存在: " + id));
        return new MeetingDetailVO(
                m.getId(), m.getTitle(), m.getTranscription(),
                m.getDuration(), m.getFileSize(), m.getStatus(),
                m.getCreatedAt(), m.getDialogueId(), m.getMdFilePath(),
                m.getMeetingDate());
    }

    public Page<MeetingListVO> listAll(Pageable pageable) {
        return meetingRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(m -> new MeetingListVO(
                        m.getId(), m.getTitle(), m.getStatus(),
                        m.getCreatedAt(), m.getMeetingDate(),
                        m.getFileSize(), m.getParticipants()));
    }

    @Transactional
    public void deleteMeeting(Long id) {
        MeetingMinutes meeting = meetingRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("会议不存在: " + id));

        // Capture file paths before DB deletion
        String filePath = meeting.getFilePath();
        String mdFilePath = meeting.getMdFilePath();

        vectorRepository.deleteByMeetingId(id);
        meetingRepository.deleteById(id);

        // Clean up physical files after transaction commits
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteFileIfExists(filePath);
                deleteFileIfExists(mdFilePath);
                // Also delete sidecar transcription file
                if (filePath != null) {
                    deleteFileIfExists(filePath + ".transcription.md");
                }
            }
        });
    }

    private void deleteFileIfExists(String pathStr) {
        if (pathStr == null) return;
        try {
            Path path = Path.of(pathStr);
            if (Files.exists(path)) {
                Files.delete(path);
                log.debug("Deleted physical file: {}", pathStr);
            }
        } catch (Exception e) {
            log.warn("Failed to delete physical file {}: {}", pathStr, e.getMessage());
        }
    }
}
