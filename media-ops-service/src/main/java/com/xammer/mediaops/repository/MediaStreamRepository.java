package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.MediaStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MediaStreamRepository extends JpaRepository<MediaStream, Long> {

    List<MediaStream> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<MediaStream> findByClientIdAndStatus(Long clientId, MediaStream.StreamStatus status);

    Optional<MediaStream> findByIdAndClientId(Long id, Long clientId);

    Optional<MediaStream> findByStreamKey(String streamKey);
}
