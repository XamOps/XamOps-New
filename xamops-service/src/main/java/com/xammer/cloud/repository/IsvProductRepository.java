package com.xammer.cloud.repository;

import com.xammer.cloud.domain.IsvProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IsvProductRepository extends JpaRepository<IsvProduct, Long> {

    Optional<IsvProduct> findByProductCode(String productCode);

    /** Used to authenticate ISV usage-event requests via X-Api-Key header */
    Optional<IsvProduct> findByApiKey(String apiKey);

    List<IsvProduct> findByOwnerEmail(String ownerEmail);

    List<IsvProduct> findByStatus(String status);

    boolean existsByProductCode(String productCode);
}
