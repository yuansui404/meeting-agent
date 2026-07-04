package com.meeting.document.repository;

import com.meeting.document.model.entity.DocumentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findByStatusOrderByCreatedAtDesc(String status);
    Page<DocumentEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
