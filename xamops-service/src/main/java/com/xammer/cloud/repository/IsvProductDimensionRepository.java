package com.xammer.cloud.repository;

import com.xammer.cloud.domain.IsvProductDimension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface IsvProductDimensionRepository extends JpaRepository<IsvProductDimension, Long> {

    List<IsvProductDimension> findByProductCode(String productCode);

    Optional<IsvProductDimension> findByProductCodeAndDimensionKey(String productCode, String dimensionKey);

    boolean existsByProductCodeAndDimensionKey(String productCode, String dimensionKey);

    @Modifying
    @Transactional
    void deleteByProductCode(String productCode);
}
