package com.meeting.repository;

import com.meeting.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findBySessionId(String sessionId);

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();

    boolean existsBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
