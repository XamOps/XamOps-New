package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A one-time connect token (design §2.1, table {@code cluster_connect_token}).
 *
 * <p>The raw token value is never stored; only its lowercase-hex SHA-256
 * ({@link #tokenHash}) and a {@code xct_}-prefixed display prefix
 * ({@link #tokenPrefix}, safe for logs/UI). A token is used at most once — the
 * atomic {@code consume} query enforces that (design §2.2).</p>
 */
@Entity
@Table(name = "cluster_connect_token")
public class ClusterConnectToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 12)
    private String tokenPrefix;

    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    /** CONNECT_READ_ONLY | ENABLE_AUTOMATION | ROTATE_DATA_KEY | ROTATE_AUTOMATION_KEY */
    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "features", nullable = false, length = 255)
    private String features;

    @Column(name = "create_read_role", nullable = false)
    private boolean createReadRole;

    @Column(name = "permission_hash", length = 64)
    private String permissionHash;

    /** Snapshot shown to the user; never copied into config.node_scaler. */
    @Column(name = "node_scaler", length = 24)
    private String nodeScaler;

    @Column(name = "identity_mode", length = 16)
    private String identityMode;

    /** The §5.3 node-removal predicate, evaluated at issue (ENABLE_AUTOMATION). */
    @Column(name = "node_removal", nullable = false)
    private boolean nodeRemoval;

    @Column(name = "issued_by_user_id", nullable = false)
    private Long issuedByUserId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_from_ip", length = 45)
    private String usedFromIp;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }

    public Long getClusterConfigId() { return clusterConfigId; }
    public void setClusterConfigId(Long clusterConfigId) { this.clusterConfigId = clusterConfigId; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getFeatures() { return features; }
    public void setFeatures(String features) { this.features = features; }

    public boolean isCreateReadRole() { return createReadRole; }
    public void setCreateReadRole(boolean createReadRole) { this.createReadRole = createReadRole; }

    public String getPermissionHash() { return permissionHash; }
    public void setPermissionHash(String permissionHash) { this.permissionHash = permissionHash; }

    public String getNodeScaler() { return nodeScaler; }
    public void setNodeScaler(String nodeScaler) { this.nodeScaler = nodeScaler; }

    public String getIdentityMode() { return identityMode; }
    public void setIdentityMode(String identityMode) { this.identityMode = identityMode; }

    public boolean isNodeRemoval() { return nodeRemoval; }
    public void setNodeRemoval(boolean nodeRemoval) { this.nodeRemoval = nodeRemoval; }

    public Long getIssuedByUserId() { return issuedByUserId; }
    public void setIssuedByUserId(Long issuedByUserId) { this.issuedByUserId = issuedByUserId; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public String getUsedFromIp() { return usedFromIp; }
    public void setUsedFromIp(String usedFromIp) { this.usedFromIp = usedFromIp; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
