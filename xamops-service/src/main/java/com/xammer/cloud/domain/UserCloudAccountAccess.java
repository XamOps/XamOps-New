package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

@Entity
@Table(name = "user_cloud_account_access")
@Data
@NoArgsConstructor
public class UserCloudAccountAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    /**
     * The PO14_RECOMMENDED per-user AutoSpotting write grant (design-core.md §11, PO-14). Additive,
     * default false: no per-user write capability exists today (F-06), and the column is inert while
     * {@code autospotting.authz.mode=DEFAULT}. Set by the client owner in the sub-user access screen
     * once PO14_RECOMMENDED is lifted (a later phase).
     */
    @Column(name = "autospotting_write", nullable = false)
    private boolean autospottingWrite;

    public UserCloudAccountAccess(User user, CloudAccount cloudAccount) {
        this.user = user;
        this.cloudAccount = cloudAccount;
    }
}
