package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_migration_log. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_migration_log")
@Getter
@Setter
@NoArgsConstructor
public class XpMigrationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version")
    private String version;

    @Column(name = "checksum")
    private String checksum;

    @Column(name = "applied_at")
    private Instant appliedAt;

}
