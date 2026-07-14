package com.meeting.template.repository;

import com.meeting.template.model.entity.TemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, Long> {
    List<TemplateEntity> findAllByOrderByCreatedAtDesc();
}