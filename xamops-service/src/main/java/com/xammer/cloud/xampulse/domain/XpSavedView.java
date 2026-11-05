package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_saved_view. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_saved_view")
@Getter
@Setter
@NoArgsConstructor
public class XpSavedView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "name")
    private String name;

    @Column(name = "kind")
    private String kind;

    @Column(name = "shared")
    private boolean shared;

    @Column(name = "state_json", columnDefinition = "text")
    private String stateJson;

    @Column(name = "created_at")
    private Instant createdAt;

}
