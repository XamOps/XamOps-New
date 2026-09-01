package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Maps a tenant's Authentik group to an AWS account and IAM Identity Center
 * permission set. Used to surface AWS context on the AccessOps Groups page.
 *
 * The actual AWS access grant/revoke happens automatically via the
 * Authentik SCIM provider syncing group membership to AWS IAM Identity Center.
 * No extra API call is needed — adding a user to the Authentik group is sufficient.
 */
@Entity
@Table(
    name = "client_aws_access",
    uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "authentik_group_id"})
)
@Data
@NoArgsConstructor
public class ClientAwsAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** UUID of the Authentik group (core/groups endpoint `pk` field). */
    @Column(name = "authentik_group_id", nullable = false, length = 255)
    private String authentikGroupId;

    /** Full group name in Authentik, e.g. "Acme-DevOps". */
    @Column(name = "group_name", nullable = false, length = 255)
    private String groupName;

    @Column(name = "aws_account_id", length = 255)
    private String awsAccountId;

    @Column(name = "aws_account_name", length = 255)
    private String awsAccountName;

    /** IAM Identity Center permission set name, e.g. "AdministratorAccess". */
    @Column(name = "permission_set_name", length = 255)
    private String permissionSetName;
}
