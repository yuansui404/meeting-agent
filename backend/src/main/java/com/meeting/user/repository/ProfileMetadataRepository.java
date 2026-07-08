package com.meeting.user.repository;

import com.meeting.user.model.entity.ProfileMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileMetadataRepository extends JpaRepository<ProfileMetadataEntity, Long> {

    Optional<ProfileMetadataEntity> findByFilename(String filename);

    List<ProfileMetadataEntity> findAllByEnabledTrueOrderByFilenameAsc();

    void deleteByFilename(String filename);
}
