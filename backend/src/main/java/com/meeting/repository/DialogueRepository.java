package com.meeting.repository;

import com.meeting.entity.Dialogue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DialogueRepository extends JpaRepository<Dialogue, Long> {

    List<Dialogue> findByStatusOrderByUpdatedAtDesc(String status);

    List<Dialogue> findTop10ByOrderByUpdatedAtDesc();
}
