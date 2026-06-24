package com.meeting.repository;

import com.meeting.entity.MeetingVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingVectorRepository extends JpaRepository<MeetingVector, Long> {

    List<MeetingVector> findByMeetingId(Long meetingId);
}
