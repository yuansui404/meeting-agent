package com.meeting.repository;

import com.meeting.entity.MeetingMinutes;
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

    List<MeetingMinutes> findByDialogueId(Long dialogueId);

    // 分页查询（按创建时间倒序）
    Page<MeetingMinutes> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 标题模糊匹配
    @Query(value = "SELECT * FROM meeting_minutes WHERE title ILIKE '%' || :keyword || '%' ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<MeetingMinutes> searchByTitleKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query(value = "SELECT * FROM meeting_minutes WHERE to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(transcription, '') || ' ' || coalesce(participants, '')) @@ plainto_tsquery('simple', ?1)", nativeQuery = true)
    List<MeetingMinutes> fullTextSearch(String query);

    @Query(value = "SELECT * FROM meeting_minutes WHERE coalesce(title, '') || ' ' || coalesce(transcription, '') || ' ' || coalesce(participants, '') ILIKE '%' || ?1 || '%' ORDER BY similarity(coalesce(title, '') || ' ' || coalesce(transcription, '') || ' ' || coalesce(participants, ''), ?1) DESC", nativeQuery = true)
    List<MeetingMinutes> trigramSearch(String query);

    @Query(value = "SELECT * FROM meeting_minutes WHERE coalesce(participants, '') ILIKE '%' || ?1 || '%'", nativeQuery = true)
    List<MeetingMinutes> searchByParticipants(String keyword);
}
