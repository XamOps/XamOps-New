# 03 — Onboarding and BYO-S3 Design

Scope: how a customer goes from "AWS account already connected to XamOps" to "logs and metrics flowing, archived into a bucket they own", in **one screen and one primary button**, with every trust signal visible. Cross-refs: endpoints in 06, classes in 04, screens in 07, components in 05.

## 1. Principles

1. **One screen, one button.** The Connect page (`/observability-v1/connect`) shows three cards on one page (Servers, Storage, Install) plus a permanent Trust panel. The primary button reads "Connect N servers". No multi-page wizard; details are progressive disclosure inside each card.
2. **Start from what XamOps already knows.** The account selector is prefilled from `AccountContext`; EC2 discovery uses the existing inventory (`InventoryProjectionService.byType(cloudAccountId, "EC2 Instance")`, `isFresh` check, live `Ec2Client.describeInstances` fallback via `AwsClientProvider.getEc2Client`).
3. **Two install paths, both first-class.**
   - **Copy-paste command** works on any Linux server (EC2, on-prem, other clouds) and needs **no new AWS permissions**.
   - **One-click (SSM)** needs the opt-in `XamOps-Obs-*` permissions stack (section 3), shown as a policy preview with the exact JSON before the customer approves.
4. **Read-only default.** Installing never modifies instance IAM, security groups or instance metadata options. If a prerequisite is missing (for example the SSM instance profile) we show the fix as a ready-made CloudFormation/Terraform snippet; the customer applies it.
5. **No inbound ports. Outbound 443 to one hostname** (`obs-ingest.<domain>`), shown in the Trust panel and verified in pre-flight.
6. **Reversible.** One-click uninstall (button, SSM document, or local command), token revocation, stack removal instructions.
7. **Never use the legacy shared agent key.** The installer accepts only an enrollment token created by this flow.

## 2. Flow

### 2.1 Page sequence (single page, states A to H)

| State | What the user sees | What happens |
|---|---|---|
| A. Landing | Account selector (prefilled), "Servers" card loading skeleton, Trust panel collapsed summary | `GET /api/obs/v1/onboarding/accounts/{cloudAccountId}/discovery` |
| B. Discovered | Table of EC2 instances (name, id, region, state, platform, tags, ASG, pre-flight badge), filters (region, tag key/value, ASG, state, "not yet connected"), "Select all N matching filter" | Pre-flight Level 1 (control plane only) runs automatically for visible rows |
| C. Selected | Sticky summary: "12 servers, 3 regions" and the Storage card enabled | User ticks rows or uses select-all-matching (stored as a **selector**, not as ids, for >200 rows) |
| D. Storage chosen | Storage card shows chosen mode: XamOps-managed (default), Existing bucket, Create new bucket. Validation result inline | `POST /api/obs/v1/storage/destinations/validate` for BYO |
| E. Review | Install card shows method toggle (One-click / Copy command), "Sources" checkboxes (defaults on: system logs, auth, journald, nginx/apache if detected, Docker containers, host metrics), policy preview link | `POST /api/obs/v1/onboarding/preflight` for Level 2 where possible |
| F. Installing | Button replaced by progress rail: per server Pending → Pre-flight → Installing → Waiting for first data → Connected | `POST /api/obs/v1/onboarding/jobs` then poll `GET /api/obs/v1/onboarding/jobs/{jobId}` every 2 s |
| G. Done | "12 of 12 connected" with links "Open Hosts", "Open Logs for these servers", "Invite a teammate" | Job `COMPLETED` |
| H. Partial/failed | Failed rows show specific reason code and a fix action (retry, show fix, skip) | Job `COMPLETED_WITH_ERRORS` |

Copy-paste path replaces F with: the command box (copy button, "reveals token once" note), plus the same live rail that flips each server as it enrolls (the rail is driven by enrollment events, not SSM).

### 2.2 Sequence (one-click)

```mermaid
sequenceDiagram
  participant U as User (browser)
  participant API as Spring /api/obs/v1
  participant AWS as Customer AWS (Operator role)
  participant H as EC2 host
  participant AG as /api/obs/v1/agent
  U->>API: POST onboarding/jobs {targets, storage, sources, method=SSM}
  API->>API: requireAccess(user, cloudAccountId); create obs_install_job + targets; mint enrollment token
  API->>AWS: ssm:SendCommand XamOps-Obs-InstallAgent (per region, batches of 50)
  AWS->>H: run document (download, verify sha256, install, start)
  H->>AG: POST register {enrollmentToken, identity}
  AG-->>H: ingestToken, config etag
  H->>AG: heartbeat + OTLP data begins
  U->>API: GET onboarding/jobs/{id} (poll 2s)
  API-->>U: targets[].state = WAITING_FOR_DATA then CONNECTED
```

### 2.3 Job state machine

Target state: `PENDING → PREFLIGHT → (BLOCKED | INSTALLING) → WAITING_FOR_DATA → CONNECTED`; terminal failures: `FAILED(code)`, `SKIPPED`, `CANCELLED`. Timeouts: INSTALLING 10 min; WAITING_FOR_DATA 5 min then `FAILED(NO_DATA_AFTER_INSTALL)` with the specific hint (egress or DNS). Job state: `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `CANCELLED`. A target is `CONNECTED` when the host has sent a heartbeat **and** at least one metric batch; the UI also shows `firstLogAt` and `firstMetricAt` independently.

## 3. AWS permissions: the opt-in `XamOps-Obs-*` stack

Template files (new, planned): `ROOT/cloudformation/xamops-observability-v1-permissions.yaml` (roles + SSM documents), `ROOT/cloudformation/xamops-observability-v1-bucket.yaml` (optional dedicated bucket + CMK). Style follows `xamops-readonly-role.yml` (ExternalId trust, `AllowedPattern '^\d{12}$'` on account id, `MinLength: 10` on ExternalId, scoped resources with a comment wherever `Resource: '*'` is unavoidable) and `xamops-cur-export-nested.yaml` (dedicated bucket so scope stays tight). Stack name pattern `XamOps-Obs-{cloudAccountId}` (matching the `XamOps-Obs-*` scoping convention); bucket names `xamops-logs-{awsAccountId}-{region}`.

Customer approval UX follows the existing "preview then execute" precedent (`AccountManagerController` stack-update preview/execute): the customer sees the template diff and IAM policy JSON, then opens a CloudFormation quick-create link. XamOps never gets `iam:CreateRole`; the **customer's own CloudFormation session** creates the roles. `POST /api/obs/v1/accounts/{cloudAccountId}/permission-stack/launch-url` builds the quick-create URL (template URL, stack name, parameters); `POST .../verify` runs `DescribeStacks` through the existing readonly connection role (`ReadOnlyAccess` includes `cloudformation:Describe*`), reads outputs (role ARNs), test-assumes each role with the ExternalId, and stores the result in `obs_permission_stack`.

Parameters: `XamOpsAccountId`, `ExternalId` (= `cloud_account.external_id`), `XamOpsOperatorPrincipalArn`, `XamOpsWriterPrincipalArn`, `XamOpsReaderPrincipalArn` (all from `obs.aws.*` properties), `LogBucketName` (optional), `KmsKeyArn` (optional), `EnableSsmDocuments` (default true), `EnableManagedInstallAssociation` (default false).

### 3.1 Trust policy (all three roles)

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "AWS": "<XamOpsOperatorPrincipalArn | XamOpsWriterPrincipalArn | XamOpsReaderPrincipalArn>" },
    "Action": "sts:AssumeRole",
    "Condition": { "StringEquals": { "sts:ExternalId": "<ExternalId>" } }
  }]
}
```

### 3.2 `XamOpsObsOperator` (install/uninstall via SSM only)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "RunOnlyXamOpsObsDocuments",
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ssm:*:<AccountId>:document/XamOps-Obs-InstallAgent",
        "arn:aws:ssm:*:<AccountId>:document/XamOps-Obs-UninstallAgent",
        "arn:aws:ssm:*:<AccountId>:document/XamOps-Obs-Preflight"
      ]
    },
    {
      "Sid": "TargetInstances",
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": "arn:aws:ec2:*:<AccountId>:instance/*",
      "Condition": { "StringEquals": { "ssm:resourceTag/xamops:obs": "enabled" } }
    },
    {
      "Sid": "ReadCommandResults",
      "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations", "ssm:ListCommands"],
      "Resource": "*"
    },
    {
      "Sid": "ManagedInstallAssociationOptional",
      "Effect": "Allow",
      "Action": ["ssm:CreateAssociation", "ssm:UpdateAssociation", "ssm:DeleteAssociation", "ssm:DescribeAssociation"],
      "Resource": [
        "arn:aws:ssm:*:<AccountId>:document/XamOps-Obs-*",
        "arn:aws:ssm:*:<AccountId>:association/*",
        "arn:aws:ec2:*:<AccountId>:instance/*"
      ]
    }
  ]
}
```

Notes: `ssm:GetCommandInvocation`/`ListCommands` do not support resource-level ARNs for this use, so `Resource: "*"` carries a template comment. The `SendCommand` on instances is limited by tag `xamops:obs=enabled`. For a one-click install, `ObsInstallOrchestrator` first tags the chosen instances `xamops:obs=enabled` (the Connect page tells the user this before they press the button). The permission needed is `ec2:CreateTags` restricted to that single tag key and value, and it is part of this role:

```json
{
  "Sid": "TagOnlyOurMarker",
  "Effect": "Allow",
  "Action": "ec2:CreateTags",
  "Resource": "arn:aws:ec2:*:<AccountId>:instance/*",
  "Condition": {
    "ForAllValues:StringEquals": { "aws:TagKeys": ["xamops:obs"] },
    "StringEquals": { "aws:RequestTag/xamops:obs": "enabled" }
  }
}
```

`aws:RequestTag` is not evaluated for `DeleteTags`, so uninstall uses its own statement conditioned on the tag keys only (and on the resource still carrying our marker):

```json
{
  "Sid": "UntagOnlyOurMarker",
  "Effect": "Allow",
  "Action": "ec2:DeleteTags",
  "Resource": "arn:aws:ec2:*:<AccountId>:instance/*",
  "Condition": {
    "ForAllValues:StringEquals": { "aws:TagKeys": ["xamops:obs"] },
    "StringEquals": { "ec2:ResourceTag/xamops:obs": "enabled" }
  }
}
```

Both `TagOnlyOurMarker` (CreateTags) and `UntagOnlyOurMarker` (DeleteTags) live in `policies/operator.json.tpl` and are listed, with a plain-language line each, in the Trust manifest policy list (section 11) and therefore in the Trust panel's policy preview. Rationale: the tag is a visible, auditable allow-list the customer can remove to cut XamOps off from an instance instantly.

### 3.3 `XamOpsObsLogWriter`

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "WriteArchivePrefixOnly",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:PutObjectTagging", "s3:GetObject", "s3:AbortMultipartUpload"],
      "Resource": "arn:aws:s3:::<Bucket>/<Prefix>xamops-logs/*"
    },
    {
      "Sid": "BucketMeta",
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucketMultipartUploads"],
      "Resource": "arn:aws:s3:::<Bucket>"
    },
    {
      "Sid": "ListOwnPrefix",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<Bucket>",
      "Condition": { "StringLike": { "s3:prefix": ["<Prefix>xamops-logs/*"] } }
    },
    {
      "Sid": "UseOneKey",
      "Effect": "Allow",
      "Action": ["kms:GenerateDataKey", "kms:Decrypt", "kms:DescribeKey"],
      "Resource": "<KmsKeyArn>",
      "Condition": { "StringEquals": { "kms:ViaService": "s3.<Region>.amazonaws.com" } }
    }
  ]
}
```

`<KmsKeyArn>` is always a concrete ARN. When the user picks the AWS-managed key (or sends `kmsKeyArn: null`), `DestinationValidationService` resolves it with `kms:DescribeKey` on `alias/aws/s3` in the bucket region during validation and stores the returned ARN in `obs_storage_destination.kms_key_arn`; the policy templates are rendered only from the stored ARN. **Archiver requirement:** the archiver must send `x-amz-server-side-encryption: aws:kms` (plus `x-amz-server-side-encryption-aws-kms-key-id` with the stored ARN) on every `PutObject` and every `CreateMultipartUpload`, because the bucket policy in 3.5 denies anything else. **SSE-S3-only buckets:** validation returns `KMS_ENCRYPT` = WARN ("bucket default encryption is SSE-S3; we recommend SSE-KMS"); the Writer/Reader templates then omit the KMS statement, the archiver sends `x-amz-server-side-encryption: AES256`, and the `DenyUnencryptedLogWrites` bucket-policy statement is not offered (a variant accepting `AES256` is shown instead). The destination row stores `encryption_mode` (`SSE_KMS|SSE_S3`) to drive this.

There is deliberately **no `s3:DeleteObject`**: compaction tags originals `xo-compacted=true` and a lifecycle rule expires them (section 7). `<Prefix>` is empty or ends with `/`.

### 3.4 `XamOpsObsLogReader`

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadArchive",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::<Bucket>/<Prefix>xamops-logs/*"
    },
    {
      "Sid": "BucketMeta",
      "Effect": "Allow",
      "Action": "s3:GetBucketLocation",
      "Resource": "arn:aws:s3:::<Bucket>"
    },
    {
      "Sid": "ListArchive",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<Bucket>",
      "Condition": { "StringLike": { "s3:prefix": ["<Prefix>xamops-logs/*", "<Prefix>xamops-logs/"] } }
    },
    {
      "Sid": "DecryptOnly",
      "Effect": "Allow",
      "Action": ["kms:Decrypt", "kms:DescribeKey"],
      "Resource": "<KmsKeyArn>",
      "Condition": { "StringEquals": { "kms:ViaService": "s3.<Region>.amazonaws.com" } }
    }
  ]
}
```

`GetBucketLocation` has no `s3:prefix` context key, so it is a separate unconditioned `BucketMeta` statement (a condition on it would make it implicitly denied). Reader session duration 1 hour, max 12 h by role setting; the cold worker refreshes.

### 3.5 Bucket policy for **bring-your-own** buckets (shown in UI, applied by customer)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyInsecureTransport",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::<Bucket>", "arn:aws:s3:::<Bucket>/*"],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } }
    },
    {
      "Sid": "DenyUnencryptedLogWrites",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::<Bucket>/<Prefix>xamops-logs/*",
      "Condition": { "StringNotEquals": { "s3:x-amz-server-side-encryption": "aws:kms" } }
    }
  ]
}
```

Because the Writer/Reader roles live in the **same account as the bucket**, no cross-account bucket-policy grant is needed (identity-based permissions suffice). That removes the most common source of cross-account S3 mistakes. Cross-account bucket (bucket in another account than the connected one) is out of scope for 1.0 and flagged in validation with a clear message.

### 3.6 Optional instance-profile snippet (shown only when pre-flight finds no SSM)

```yaml
XamOpsObsSsmRole:
  Type: AWS::IAM::Role
  Properties:
    AssumeRolePolicyDocument:
      Version: "2012-10-17"
      Statement: [{ Effect: Allow, Principal: { Service: ec2.amazonaws.com }, Action: sts:AssumeRole }]
    ManagedPolicyArns: [arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore]
```

Nothing else is needed on the host: the agent reaches XamOps over the internet/VPC egress and uses no AWS credentials. (The host never needs S3 access in the default mode.)

### 3.7 SSM documents created by the stack

`XamOps-Obs-InstallAgent` (parameters `EnrollmentToken`, `IngestHost`, `AgentVersion`, `Sources`), `XamOps-Obs-UninstallAgent`, `XamOps-Obs-Preflight`. Each is a `Command` document, `schemaVersion 2.2`, `runShellScript` (and `runPowerShellScript` at 1.0 GA), downloading `https://<app-domain>/obs-agent/install.sh` and passing arguments by environment variable (no secrets echoed to logs; `set +x`). Because SSM command parameters appear in the customer's SSM/CloudTrail history, the enrollment token is single-use per host, expires in 60 minutes and is not an ingest credential; this is stated in the Trust panel.

## 4. Pre-flight checks

Two levels. Level 1 runs in the control plane from inventory/AWS Describe calls through the existing readonly connection role. Level 2 runs on the host (SSM document `XamOps-Obs-Preflight`, or the installer's `--preflight` flag in the copy-paste path), is read-only and prints JSON.

| Code | Level | Check | Source | Severity | Fix shown |
|---|---|---|---|---|---|
| `PF_STATE` | 1 | Instance is `running` | `DescribeInstances` | Blocker | "Start the instance, or untick it" |
| `PF_SSM_MANAGED` | 1 | Registered in SSM and `PingStatus=Online` | `ssm:DescribeInstanceInformation` | Blocker for one-click only | Switch to copy-paste, or apply instance-profile snippet (3.6) |
| `PF_STACK_MISSING` | 1 | `obs_permission_stack` verified | DB | Blocker for one-click only | "Enable one-click" opens the policy preview |
| `PF_OS` | 1+2 | Linux (Amazon Linux 2/2023, Ubuntu 18.04+, Debian 10+, RHEL/Rocky/Alma 7+, SUSE 15) with systemd; Windows Server 2016+ from 1.0 GA | Platform details + `/etc/os-release` | Blocker | "Unsupported OS: use copy-paste with container mode" |
| `PF_ARCH` | 1+2 | `x86_64` or `aarch64` | instance type / `uname -m` | Blocker | n/a |
| `PF_IMDS` | 2 | IMDSv2 token request succeeds (hop limit ≥ 1 on host) | script | Blocker | "Enable IMDS or pass --instance-id manually" |
| `PF_EGRESS` | 2 | `curl -sS --max-time 5 https://obs-ingest.<domain>/healthz` returns 200 | script | Blocker | Shows host, port 443, and proxy flags (`--proxy`) |
| `PF_DNS` | 2 | Hostname resolves | script | Blocker | n/a |
| `PF_CLOCK` | 2 | Skew vs ingest `Date` header < 30 s | script | Warning | "Enable chrony" |
| `PF_DISK` | 2 | ≥ 1 GB free in `/var/lib` | script | Warning (<1 GB blocker) | n/a |
| `PF_PERMS_LOGS` | 2 | Planned log paths readable by group `adm`/ACL possible | script | Warning | ACL command |
| `PF_CONFLICT_AGENT` | 2 | Detect legacy XamOps agent, Datadog, CloudWatch agent, Fluent Bit, OTel | script | Info (never blocks) | Explains coexistence |
| `PF_SELINUX` | 2 | SELinux enforcing: warn and include policy module step | script | Warning | n/a |

Output of Level 2 is stored in `obs_install_job_target.preflight_json` and displayed per server as chips: StatusMark with word (never color alone): "Ready", "Needs attention (2)", "Blocked".

## 5. Install methods

### 5.1 Copy-paste (default for non-EC2 and for accounts without the stack)

`POST /api/obs/v1/onboarding/install-command` returns:

```json
{
  "command": "curl -fsSL https://app.xamops.example/obs-agent/install.sh | sudo bash -s -- --token xo_enr_AbC123... --sources system,auth,docker,nginx",
  "enrollmentTokenExpiresAt": "2026-11-02T10:00:00Z",
  "maxUses": 12,
  "checksumUrl": "https://app.xamops.example/obs-agent/download/1.0.0/manifest.json",
  "uninstallCommand": "sudo /opt/xamops-obs/bin/xamops-obs-agent uninstall"
}
```

Installer behaviour (`xamops-agent/scripts/obs_install.sh`, served by `ObsInstallScriptController` from the classpath resource `observability/agent-install/obs_install.sh`, same pattern as `AgentInstallController`):

1. `set -euo pipefail`; refuse to run without systemd; detect `uname -m`.
2. IMDSv2 token only; no IMDSv1 fallback.
3. Download `manifest.json` then the tarball for the exact version; **verify SHA-256 (and signature at 1.0 GA)** before executing anything; print the verified digest.
4. Create user `xamops-obs`, directories, systemd units; write the enrollment token to a root-only file and delete it after registration; `--token` is also accepted via `XAMOPS_OBS_TOKEN` env (so it need not appear in `ps`).
5. `--dry-run` prints exactly what it would do; `--preflight` runs section 4 Level 2 only.
6. Never accepts passwords on the command line, never embeds any shared key.

Manual servers (non-EC2) register without AWS identity verification; their rows show "Not an AWS instance" and group under the selected cloud account they were enrolled for.

### 5.2 One-click (SSM Run Command)

`ObsInstallOrchestrator` sends `XamOps-Obs-InstallAgent` per region in batches of 50 instance ids (`SendCommand` MaxConcurrency 20%, MaxErrors 10%), polls `GetCommandInvocation`, maps failures to codes (`SSM_TIMEOUT`, `SCRIPT_EXIT_<n>`, `DOWNLOAD_FAILED`, `CHECKSUM_MISMATCH`, `EGRESS_BLOCKED`). All instance ids and regions pass `RemoteCommandInputValidator.requireValidInstanceId` / region regex before use.

### 5.3 Bulk selection, Auto Scaling groups, tags

- Selection is stored as `selector` JSON (`{"mode":"all"|"filter"|"ids","regions":[],"tags":{"k":"v"},"asgNames":[],"states":["running"],"ids":[]}`) so "all in account/region/tag" scales beyond a page.
- **Auto Scaling:** for an ASG the page offers "Install on launch": generates (a) a launch-template user-data snippet and (b) an optional **State Manager association** (`XamOps-Obs-InstallAgent`, target by tag `xamops:obs=enabled`, runs on instance launch/every 30 min) when the stack is present. New instances auto-enroll with a **group enrollment token** (`obs_host_group` with `max_uses` 1000, TTL 90 days, rotation reminder). Termination: a daily reconcile (`ObsHostReconcileJob`) compares registered hosts against inventory; terminated instances are marked `TERMINATED` and stop counting against caps.

### 5.4 Infrastructure-as-code outputs (`POST /api/obs/v1/onboarding/snippets`)

`kind` = `USER_DATA | LAUNCH_TEMPLATE | CLOUDFORMATION | TERRAFORM | ECS_TASK | EKS_DAEMONSET | PACKER_AMI`. Examples:

```bash
#!/bin/bash
# user-data (Amazon Linux / Ubuntu)
export XAMOPS_OBS_TOKEN="xo_enr_...group token..."
curl -fsSL https://app.xamops.example/obs-agent/install.sh | bash -s -- --sources system,auth,docker
```

```hcl
# Terraform: tag instances so the one-click association picks them up
resource "aws_instance" "app" {
  # ...
  tags = { "xamops:obs" = "enabled" }
  user_data = templatefile("${path.module}/xamops-obs-userdata.sh.tpl", { token = var.xamops_obs_token })
}
```

```yaml
# CloudFormation: State Manager association (requires the XamOps-Obs permissions stack)
XamOpsObsInstall:
  Type: AWS::SSM::Association
  Properties:
    Name: XamOps-Obs-InstallAgent
    Targets: [{ Key: tag:xamops:obs, Values: [enabled] }]
    Parameters: { EnrollmentToken: ["{{ssm-secure:/xamops/obs/enrollment}}"], IngestHost: ["obs-ingest.example.com"] }
    ScheduleExpression: "rate(30 minutes)"
```

ECS (on EC2): daemon-scheduled task running the supervisor image `xamops/obs-agent` with host mounts `/var/lib/docker/containers:ro`, `/var/run/docker.sock:ro`, `/proc:ro`. EKS: DaemonSet manifest/Helm values (node-level logs from `/var/log/pods`, kubelet stats) at 1.0; workload depth in 2.0. Packer/AMI: install script with `--no-start --bake` plus first-boot enrollment.

## 6. S3 bucket layout and file formats

### 6.1 Key layout (exact)

```
s3://{bucket}/{prefix}xamops-logs/{awsAccountId}/{region}/{host-or-service}/{type}/yyyy/mm/dd/hh/part-ca{cloudAccountId}-{yyyymmddhhmm}-{seq}.parquet
s3://{bucket}/{prefix}xamops-logs/{awsAccountId}/{region}/{host-or-service}/{type}/yyyy/mm/dd/hh/compact-ca{cloudAccountId}-{yyyymmddhh}.parquet
s3://{bucket}/{prefix}xamops-logs/_manifest/ca{cloudAccountId}/yyyy/mm/dd/hh.json
```

Default `{prefix}` is empty. `{awsAccountId}` is the 12-digit id (human-browsable, as requested). Because one AWS account can be connected twice, file names carry `ca{cloudAccountId}`, every row carries `tenant_hash`, and both Writer and Reader policies are the same-account roles of **that** connection, so rows of two XamOps tenants never mix in a query. `{host-or-service}` and `{type}` rules are in 02 section 4.4. `yyyy/mm/dd/hh` is UTC of the event timestamp (not ingest time).

### 6.2 Formats

| Option (`obs_storage_destination.format`) | Extension | Notes |
|---|---|---|
| `parquet` (default) | `.parquet` | ZSTD level 3 column compression, row group 128 MB, dictionary encoding on low-cardinality columns; columns in 06 section 3.4 |
| `jsonl_zst` | `.jsonl.zst` | One JSON object per line, same fields; for customers who want `zstdcat` + `grep` |

Metrics archive: `type=metrics`, one row per series per minute (min/max/avg/count), same layout.

### 6.3 Query-ability

Manifest rows in Postgres drive file selection (no LIST). For customers who want to query in their own account, the Storage page shows Athena DDL generated with **partition projection** (works with this non-Hive layout):

```sql
CREATE EXTERNAL TABLE xamops_logs (
  ts timestamp, ingest_ts timestamp, tenant_hash string, account string, region string,
  host string, service string, type string, level string, message string,
  trace_id string, span_id string, pattern_id string, attributes map<string,string>)
PARTITIONED BY (acct string, reg string, hostsvc string, logtype string, dt string)
STORED AS PARQUET
LOCATION 's3://<bucket>/<prefix>xamops-logs/'
TBLPROPERTIES (
  'projection.enabled'='true',
  'projection.acct.type'='enum','projection.acct.values'='<awsAccountId>',
  'projection.reg.type'='enum','projection.reg.values'='<regions>',
  'projection.hostsvc.type'='injected','projection.logtype.type'='enum',
  'projection.logtype.values'='application,container,system,auth,web,windows-event,metrics',
  'projection.dt.type'='date','projection.dt.format'='yyyy/MM/dd/HH','projection.dt.range'='2026/01/01/00,NOW','projection.dt.interval'='1','projection.dt.interval.unit'='HOURS',
  'storage.location.template'='s3://<bucket>/<prefix>xamops-logs/${acct}/${reg}/${hostsvc}/${logtype}/${dt}/');
```

## 7. Lifecycle, retention, encryption

Rules generated for XamOps-created buckets and shown as a copyable JSON for BYO buckets (the customer applies; XamOps never edits an existing bucket's lifecycle in 1.0):

```json
{
  "Rules": [
    { "ID": "xamops-logs-tiering", "Status": "Enabled",
      "Filter": { "Prefix": "xamops-logs/" },
      "Transitions": [
        { "Days": 30, "StorageClass": "STANDARD_IA" },
        { "Days": 90, "StorageClass": "GLACIER_IR" } ],
      "Expiration": { "Days": 365 } },
    { "ID": "xamops-logs-expire-compacted", "Status": "Enabled",
      "Filter": { "Tag": { "Key": "xo-compacted", "Value": "true" } },
      "Expiration": { "Days": 1 } },
    { "ID": "xamops-abort-mpu", "Status": "Enabled",
      "Filter": { "Prefix": "xamops-logs/" },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 } }
  ]
}
```

Retention presets in the UI: 7 / 30 / 90 / 180 / 365 days / custom (default 365); minimum 7, maximum 3650. The selected value sets `Expiration.Days` and is stored in `obs_storage_destination.retention_days`. The JSON above is the 365-day example; the real rule comes from `LifecycleRuleGenerator` (in `service/storage/`), which emits a transition **only when its `Days` is strictly less than `Expiration.Days`**: `STANDARD_IA` at 30 only if retention > 30, `GLACIER_IR` at 90 only if retention > 90. S3 rejects a rule whose expiration is not greater than a transition, so e.g. retention 30 yields no transitions, retention 90 yields IA only, 180 and 365 yield both. Unit-test table (`LifecycleRuleGeneratorTest`): 7 (none), 30 (none), 31 (IA), 90 (IA), 91 (IA+GIR), 180, 365, 3650, and rejection of 6 and 3651. Note S3 minimum storage duration charges (IA 30 days, Glacier IR 90 days); the cost estimate accounts for them. `GLACIER_IR` keeps millisecond retrieval so cold queries still work; Flexible Retrieval / Deep Archive are offered as "Deep archive (restore required)" and excluded from queryable scope, with the UI marking those hours as "Archived: restore to search".

Encryption: SSE-KMS with a customer CMK (created by the bucket template, alias `alias/xamops-obs-logs`) or the AWS-managed `aws/s3` key; bucket key enabled to cut KMS cost; TLS-only policy; `BlockPublicAcls/IgnorePublicAcls/BlockPublicPolicy/RestrictPublicBuckets` all true; `BucketOwnerEnforced`; versioning off by default; no Object Lock (warn if present: it blocks the tag-expire compaction path).

## 8. Bucket selection and validation

`GET /api/obs/v1/storage/buckets?cloudAccountId=&region=` lists through `AwsClientProvider.getS3Client` (`ListBuckets` + `GetBucketLocation` per bucket, cached 60 s) and returns per bucket: name, region, createdAt, `publicAccessBlocked`, `encryption` (`none|sse-s3|sse-kms`), `versioning`, `objectLock`, `lifecycleRulesCount`, `isSameRegionAsHosts`, `warnings[]`. Buckets with public access are shown disabled with the reason. Cross-region buckets are selectable with a visible warning ("data transfer charges apply").

`POST /api/obs/v1/storage/destinations/validate` runs (each returns pass/warn/fail with message and fix):

1. `BUCKET_EXISTS` and region (HeadBucket via customer client).
2. `PUBLIC_ACCESS_BLOCK` all four true (else fail with the one-line fix).
3. `OWNERSHIP_ENFORCED`.
4. `ROLES_ASSUMABLE` Writer and Reader (test AssumeRole with ExternalId).
5. `WRITE_TEST`: PutObject `xamops-logs/_validate/ca{id}/{uuid}.txt` with the SSE-KMS header, GetObject read-back via **Reader**, then tag `xo-compacted=true` (lifecycle cleans it) because Writer has no delete.
6. `KMS_ENCRYPT`: resolves the key ARN (`DescribeKey` on `alias/aws/s3` when none was chosen, stored), then `GenerateDataKey` on it; WARN for SSE-S3-only buckets (section 3.3).
7. `LIFECYCLE_CONFLICT`: existing rules overlapping `xamops-logs/` prefix (warn).
8. `OBJECT_LOCK` / `VERSIONING` warnings.
9. `COST_ESTIMATE`: volume forecast from selected hosts × observed or default 1.5 GB/day (ratio 10x), per-class $/GB-month from `AwsClientProvider.getPricingClient()` (cached 24 h, fall back to a bundled price table marked "estimated"), monthly and 12-month totals including IA/Glacier tiering and request costs (PUT per 1,000).

Response: `{ "ok": true|false, "checks": [ ... ], "estimate": { ... } }`. Validation is idempotent and has no side effects beyond the test object.

## 9. Fallback and migration

- **Managed fallback** (default mode so a new customer can connect with zero AWS steps): the destination row has `mode=MANAGED`. UI copy: "Stored in XamOps-managed storage, encrypted with your own tenant key. You can move it to your bucket any time."
- **Move to my bucket:** `POST /api/obs/v1/storage/destinations/{id}/migrate` creates an `obs_query_job` of kind `MIGRATE` (server-side copy from managed to customer bucket using the Reader/Writer pair, preserving keys). Progress shown; new writes switch at cutover time recorded in `obs_storage_destination.cutover_at`; queries read both locations by manifest.
- **Switch back:** same mechanism in reverse (no data deleted from the customer bucket).
- **Safe fallback on failure:** 02 section 4.4.

## 10. Uninstall and rollback

| Action | Where | Effect |
|---|---|---|
| Uninstall one host | Agents page, Host detail "Disconnect", or `sudo /opt/xamops-obs/bin/xamops-obs-agent uninstall` | Command `UNINSTALL` queued (delivered on next heartbeat, within 60 s) or executed locally; removes units/binaries/config/queue; calls `/agent/uninstalled`; host row → `UNINSTALLED`; ingest token revoked |
| Uninstall many | Agents page bulk action or install job "Roll back" | Same, via SSM document `XamOps-Obs-UninstallAgent` for hosts without a live agent |
| Revoke access (kill switch) | Remove tag `xamops:obs`, or Settings → Tokens "Revoke all" | SSM targeting stops; ingest rejected with 401 within 60 s (Redis TTL) |
| Roll back an agent update | Agents page "Roll back to previous version" or automatic on failed health gate | Previous binary retained at `/opt/xamops-obs/previous` |
| Remove AWS permissions | Settings → Storage and access shows "Delete stack `XamOps-Obs-{id}`" with link | Customer deletes the CloudFormation stack; roles and SSM documents vanish; verify job marks the stack `REMOVED` |
| Delete data | Storage page "Request data deletion" (hot: purge by tenant; cold: customer owns the bucket, so shows `aws s3 rm` instructions with exact prefix) | Hot purge by VictoriaLogs delete API is not available in OSS single-node; hot data expires with retention (stated honestly in the UI); managed-bucket data deleted by `DataDeletionJob` (`service/storage/`, driven by rows in `obs_deletion_request`, see 06 section 2.6) and audited |

## 11. Trust manifest (content of the Trust panel)

Endpoint `GET /api/obs/v1/onboarding/trust-manifest?version=` returns data rendered by `TrustPanel`:

- **Installed:** binaries `xamops-obs-agent`, `xamops-otelcol` (version, SHA-256, size, install path), systemd units, user `xamops-obs`, directories, disk usage cap.
- **Collected (per source checkbox):** what files/streams, example fields, sample redaction preview; default OFF for anything not in the default set (databases, custom paths).
- **Permissions:** the three policies from section 3 with "Copy JSON" and a plain-language line per statement, including the Operator `TagOnlyOurMarker` (ec2:CreateTags) and `UntagOnlyOurMarker` (ec2:DeleteTags) statements.
- **Network:** outbound TCP 443 to `obs-ingest.<domain>`; no inbound; loopback-only listeners.
- **Data handling:** TLS in transit; redaction at host and gateway; storage in your S3 with SSE-KMS or managed bucket; retention; who can read logs (RBAC).
- **Control:** uninstall, revoke, pause collection per source, "Dry-run installer".
- **Verification:** how to verify the download checksum manually; installer source link.

## 12. Edge cases

- **Instance in a region without inventory coverage** (outside Resource Explorer): discovery falls back to live `DescribeInstances` for regions in `activeRegions` plus any region the user adds manually; slow regions are queried in parallel with a 10 s per-region cap; failures show "Region eu-west-3: couldn't load (retry)".
- **Same instance enrolled twice:** register is idempotent on `(cloud_account_id, host_key)`; a second enrollment rotates the token and keeps history.
- **Cloned AMI with baked token:** enrollment token usage cap and the instance identity check stop mass reuse; ingest tokens are never baked (only generated at register).
- **Stopped/terminated instances:** discovery shows them disabled; reconcile job marks hosts.
- **IMDS tags off:** tags are fetched by the control plane from `DescribeTags` (readonly role) and merged server-side, so the agent needs no EC2 API permissions.
- **Proxy-only egress:** `--proxy` flag and `HTTPS_PROXY` honoured by both supervisor and collector; pre-flight uses it.
- **Windows (1.0 GA):** PowerShell installer, service `XamOpsObsAgent`, Event Log channels Application/System/Security (Security requires permission toggle).
- **ECS/EKS discovery:** `ListClusters`/`DescribeClusters` via `getEcsClient`/`getEksClient` surface clusters in the Servers card under "Containers"; EC2 container instances are installed like hosts; Fargate is shown as "Not supported in 1.0 (sidecar in 2.0)".
- **Quota:** per-tenant host cap (`obs_tenant_settings.max_hosts`, default 500) enforced at enrollment with a specific error and a request-more link.

## 13. Responses to design review (iteration 2)
- Finding 1 (fixed): Reader `GetBucketLocation` moved to an unconditioned `BucketMeta` statement; `ListArchive` keeps the `s3:prefix` condition (3.4).
- Finding 2 (fixed): explicit `UntagOnlyOurMarker` (`ec2:DeleteTags`, `aws:TagKeys` + `ec2:ResourceTag`) added; both tag statements are in `operator.json.tpl` and the Trust manifest policy list (3.2, 11).
- Finding 3 (fixed): `LifecycleRuleGenerator` emits transitions only when `Days < retention`; retention 7 to 3650; presets 7/30/90/180/365; unit-test table named (7).
- Finding 4 (fixed): concrete `<KmsKeyArn>` always (alias/aws/s3 resolved by `DescribeKey` and stored), `kms:ViaService`, mandatory SSE headers on every put/multipart, SSE-S3-only behaviour defined, `encryption_mode` column (3.3, 6 in 06).
- Finding 9 (fixed, 03 part): `DataDeletionJob` and table `obs_deletion_request` (10; DDL in 06 section 2.6).
