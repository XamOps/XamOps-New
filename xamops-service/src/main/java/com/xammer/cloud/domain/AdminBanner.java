package com.xammer.cloud.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * An announcement a BillOps admin pushes to the top of XamOps client dashboards.
 *
 * <p>
 * Targets clients ({@code client.id}), the unit a XamOps user belongs to. {@code targetAll}
 * reaches every client, including ones created after the banner; otherwise only the clients in
 * {@link #targetClientIds} see it. Schema: {@code V20261103_1__admin_banners.sql}.
 */
@Entity
@Table(name = "admin_banner")
@Getter
@Setter
@NoArgsConstructor
public class AdminBanner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "heading", nullable = false, length = 200)
    private String heading;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "target_all", nullable = false)
    private boolean targetAll = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "admin_banner_target", joinColumns = @JoinColumn(name = "banner_id"))
    @Column(name = "client_id", nullable = false)
    private Set<Long> targetClientIds = new HashSet<>();

    @Column(name = "created_by")
    private String createdBy;

    /** The admin's app_user id, used to scope sub-admins to the banners they wrote. */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
