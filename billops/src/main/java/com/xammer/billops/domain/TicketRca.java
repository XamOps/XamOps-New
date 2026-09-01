package com.xammer.billops.domain;

import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_rca")
@Data
@NoArgsConstructor
public class TicketRca {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_user_id", nullable = false)
    private User generatedBy;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    public TicketRca(Ticket ticket, User generatedBy, String s3Key, String fileName) {
        this.ticket = ticket;
        this.generatedBy = generatedBy;
        this.s3Key = s3Key;
        this.fileName = fileName;
        this.generatedAt = LocalDateTime.now();
    }
}