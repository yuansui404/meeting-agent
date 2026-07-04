package com.meeting.service;

import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.meeting.repository.MeetingVectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingMinutesRepository meetingRepository;
    private final MeetingVectorRepository vectorRepository;

    @Transactional
    public void deleteMeeting(Long id) {
        vectorRepository.deleteByMeetingId(id);
        meetingRepository.deleteById(id);
    }
}
