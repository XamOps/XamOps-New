package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_script_interactions", indexes = {
    @Index(name = "idx_user_interactions", columnList = "user_id, interaction_type"),
    @Index(name = "idx_script_interactions", columnList = "script_id, interaction_type"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class UserScriptInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "interaction_type", nullable = false, length = 50)
    private String interactionType; // view, download, copy, favorite, unfavorite

    @Column(columnDefinition = "TEXT")
    private String metadata; // Additional context (JSON format)

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // Constructors
    public UserScriptInteraction() {}

    public UserScriptInteraction(String userId, Long scriptId, String interactionType) {
        this.userId = userId;
        this.scriptId = scriptId;
        this.interactionType = interactionType;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
