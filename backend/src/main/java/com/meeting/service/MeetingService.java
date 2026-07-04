package com.meeting.service;

import com.meeting.common.BusinessException;
import com.meeting.controller.dto.response.MeetingDetailVO;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.meeting.repository.MeetingVectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public Page<MeetingMinutes> listAll(Pageable pageable) {
        return meetingRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional
    public void deleteMeeting(Long id) {
        if (!meetingRepository.existsById(id)) {
            throw BusinessException.notFound("会议不存在: " + id);
        }
        vectorRepository.deleteByMeetingId(id);
        meetingRepository.deleteById(id);
    }
}
