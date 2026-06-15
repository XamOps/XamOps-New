package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.MediaStorageFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MediaStorageFileRepository extends JpaRepository<MediaStorageFile, Long> {

    List<MediaStorageFile> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<MediaStorageFile> findByClientIdAndFileTypeOrderByCreatedAtDesc(Long clientId, MediaStorageFile.FileType fileType);

    Optional<MediaStorageFile> findByIdAndClientId(Long id, Long clientId);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM MediaStorageFile f WHERE f.clientId = :clientId")
    long totalStorageByClientId(Long clientId);

    void deleteByIdAndClientId(Long id, Long clientId);
}
