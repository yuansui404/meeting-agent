package com.meeting.repository;

import com.meeting.entity.MeetingMinutes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingMinutesRepository extends JpaRepository<MeetingMinutes, Long> {

    List<MeetingMinutes> findByStatus(String status);

    List<MeetingMinutes> findByDialogueId(Long dialogueId);

    List<MeetingMinutes> findByKnowledgeBaseTrue();

    @Query(value = "SELECT * FROM meeting_minutes WHERE to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(transcription, '')) @@ plainto_tsquery('simple', ?1)", nativeQuery = true)
    List<MeetingMinutes> fullTextSearch(String query);

    @Query(value = "SELECT * FROM meeting_minutes WHERE coalesce(title, '') || ' ' || coalesce(transcription, '') ILIKE '%' || ?1 || '%' ORDER BY similarity(coalesce(title, '') || ' ' || coalesce(transcription, ''), ?1) DESC", nativeQuery = true)
    List<MeetingMinutes> trigramSearch(String query);
}
