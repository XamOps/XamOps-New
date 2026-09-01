package com.xammer.billops.domain;

import com.xammer.cloud.domain.AppUser;
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
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    public UserCloudAccountAccess(AppUser user, CloudAccount cloudAccount) {
        this.user = user;
        this.cloudAccount = cloudAccount;
    }
}
