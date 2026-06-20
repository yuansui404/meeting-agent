package com.meeting.repository;

import com.meeting.entity.MeetingVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingVectorRepository extends JpaRepository<MeetingVector, Long> {

    List<MeetingVector> findByMeetingId(Long meetingId);

    @Query(value = "SELECT * FROM meeting_vectors ORDER BY embedding <=> CAST(?1 AS vector) LIMIT ?2", nativeQuery = true)
    List<MeetingVector> vectorSearch(float[] embedding, int limit);

    void deleteByMeetingId(Long meetingId);
}
