package com.meeting.meeting.repository;

import com.meeting.meeting.model.entity.MeetingVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingVectorRepository extends JpaRepository<MeetingVector, Long> {

    List<MeetingVector> findByMeetingId(Long meetingId);

    @Query(value = "SELECT DISTINCT meeting_id FROM meeting_vectors WHERE content ILIKE '%' || :keyword || '%' LIMIT :limit", nativeQuery = true)
    List<Long> findMeetingIdsByContentLike(@Param("keyword") String keyword, @Param("limit") int limit);
}
