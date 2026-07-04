package com.meeting.meeting.repository;

import com.meeting.meeting.model.entity.MeetingMinutes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingMinutesRepository extends JpaRepository<MeetingMinutes, Long> {

    List<MeetingMinutes> findByStatus(String status);

    Page<MeetingMinutes> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query(value = "SELECT * FROM meeting_minutes WHERE title ILIKE '%' || :keyword || '%' ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<MeetingMinutes> searchByTitleKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query(value = "SELECT * FROM meeting_minutes WHERE to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(transcription, '') || ' ' || coalesce(participants, '')) @@ plainto_tsquery('simple', :query) LIMIT 50", nativeQuery = true)
    List<MeetingMinutes> fullTextSearch(@Param("query") String query);

    @Query(value = "SELECT * FROM meeting_minutes WHERE coalesce(title, '') || ' ' || coalesce(transcription, '') || ' ' || coalesce(participants, '') ILIKE '%' || :query || '%' ORDER BY similarity(coalesce(title, '') || ' ' || coalesce(transcription, '') || ' ' || coalesce(participants, ''), :query) DESC LIMIT 50", nativeQuery = true)
    List<MeetingMinutes> trigramSearch(@Param("query") String query);

    @Query(value = "SELECT * FROM meeting_minutes WHERE coalesce(participants, '') ILIKE '%' || :keyword || '%' LIMIT 50", nativeQuery = true)
    List<MeetingMinutes> searchByParticipants(@Param("keyword") String keyword);

    MeetingMinutes findByFilePath(String filePath);

    List<MeetingMinutes> findByStyleExemplarTrue();
}
