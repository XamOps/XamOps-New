package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A metering dimension registered for an ISV product on AWS Marketplace.
 * Dimensions are defined by the ISV when they list their product (e.g. "api_calls", "users", "gb_stored").
 * XamOps uses these to validate incoming usage events.
 */
@Entity
@Table(name = "isv_product_dimension",
       uniqueConstraints = @UniqueConstraint(columnNames = {"productCode", "dimensionKey"}))
@Data
@NoArgsConstructor
public class IsvProductDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to isv_product.product_code */
    @Column(nullable = false, length = 100)
    private String productCode;

    /** Dimension key as registered on AWS Marketplace (e.g., "api_calls") */
    @Column(nullable = false, length = 100)
    private String dimensionKey;

    /** Human-readable description (e.g., "Number of API calls made") */
    @Column(length = 500)
    private String description;

    /** Unit label for display (e.g., "API calls", "GB", "active users") */
    @Column(length = 100)
    private String unit;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
