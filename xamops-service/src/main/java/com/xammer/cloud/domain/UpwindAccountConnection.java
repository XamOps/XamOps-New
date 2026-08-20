package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_account_connection")
public class UpwindAccountConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Provider account id: AWS account id / GCP project id / Azure subscription id */
    @Column(name = "cloud_account_id", nullable = false, unique = true)
    private String cloudAccountId;

    /** NOT_CONNECTED / PENDING / CONNECTED */
    @Column(nullable = false)
    private String status = "NOT_CONNECTED";

    /** Helm/Bash sensor install command registered by a Xammer admin. */
    @Column(name = "sensor_install_command", columnDefinition = "TEXT")
    private String sensorInstallCommand;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * When the customer was last handed a CloudFormation launch URL for this account.
     *
     * Set on PENDING, and the anchor for the "still waiting" vs "probably failed" distinction:
     * Upwind's stack registers the account itself and assets appear within minutes, so a
     * connection still PENDING hours later means the stack was abandoned or rolled back, which
     * is worth saying rather than spinning forever.
     */
    @Column(name = "stack_requested_at")
    private Instant stackRequestedAt;

    /**
     * Region the launch URL targeted.
     *
     * Not cosmetic: Upwind's template pulls its onboarding Lambda from
     * {@code upwind-serverless-functions-${AWS::Region}}, so the stack only deploys in regions
     * where Upwind publishes that bucket. Recording what we sent makes a failed deploy
     * diagnosable instead of mysterious.
     */
    @Column(name = "stack_region")
    private String stackRegion;

    /** Stack name we generated, so support can find it in the customer's account. */
    @Column(name = "stack_name")
    private String stackName;

    /** When Upwind first reported assets for this account — i.e. proof the connection works. */
    @Column(name = "connection_verified_at")
    private Instant connectionVerifiedAt;

    /**
     * Upwind's id for the registered connection ({@code cloud-credentials[].id}).
     *
     * The handle needed to disconnect. Upwind's CloudFormation template cannot do this for us:
     * its Delete handler replies SUCCESS to CloudFormation and calls no API at all, despite
     * logging "Integration deleted successfully". So deleting the stack removes the IAM role and
     * leaves Upwind assuming a role that no longer exists — a permanently failing connection
     * rather than a removal. Disconnecting properly means DELETE-ing this id first.
     */
    @Column(name = "upwind_credential_id")
    private String upwindCredentialId;

    /** When the connection was last torn down, for support archaeology on re-onboarding. */
    @Column(name = "disconnected_at")
    private Instant disconnectedAt;
}
