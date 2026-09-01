package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "script_versions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"script_id", "version"})
})
public class ScriptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false)
    private DevOpsScript script;

    @Column(nullable = false, length = 20)
    private String version;

    // No @Lob: on PostgreSQL @Lob maps a String to a large-object OID and reads
    // it via getClob()/getLong(), failing ("Bad value for type long") against a
    // plain TEXT column. columnDefinition = "TEXT" already gives unbounded storage.
    @Column(columnDefinition = "TEXT", nullable = false)
    private String code;

    @Column(name = "change_log", columnDefinition = "TEXT")
    private String changeLog;

    @Column(name = "released_at", nullable = false)
    private LocalDateTime releasedAt;

    @Column(name = "released_by", length = 100)
    private String releasedBy;

    @Column(name = "is_deprecated")
    private Boolean isDeprecated = false;

    // Constructors
    public ScriptVersion() {}

    public ScriptVersion(DevOpsScript script, String version, String code, String changeLog, String releasedBy) {
        this.script = script;
        this.version = version;
        this.code = code;
        this.changeLog = changeLog;
        this.releasedBy = releasedBy;
        this.releasedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public DevOpsScript getScript() { return script; }
    public void setScript(DevOpsScript script) { this.script = script; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getChangeLog() { return changeLog; }
    public void setChangeLog(String changeLog) { this.changeLog = changeLog; }

    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }

    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }

    public Boolean getIsDeprecated() { return isDeprecated; }
    public void setIsDeprecated(Boolean isDeprecated) { this.isDeprecated = isDeprecated; }
}
