package com.xammer.cloud.domain.call;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * One person's relationship to a call: who was rung, who answered, who missed it.
 *
 * Rows are written up front for the whole ring set, so an unanswered call leaves
 * a MISSED row per recipient with no extra bookkeeping — that is the missed-call
 * feed.
 */
@Entity
@Table(name = "call_participant")
@Getter
@Setter
public class CallParticipant {

    public enum Party {
        INITIATOR,
        ADMIN,
        CUSTOMER
    }

    public enum Outcome {
        JOINED,
        DECLINED,
        MISSED,
        /** Initiator hung up before this participant acted. */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_session_id", nullable = false)
    private Long callSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Party party;

    /** False for the initiator, true for everyone in the fan-out set. */
    @Column(name = "was_rung", nullable = false)
    private boolean wasRung;

    /** Null while the call is still live and this participant's fate is undecided. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Outcome outcome;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;
}
