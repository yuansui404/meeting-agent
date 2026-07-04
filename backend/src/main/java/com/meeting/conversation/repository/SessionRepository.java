package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.SessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findBySessionId(String sessionId);

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();

    Page<SessionEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    boolean existsBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    @Query("SELECT s.sessionId FROM SessionEntity s")
    List<String> findAllSessionIds();
}
