# EBS Disk Rightsizing Automation — Design Spec

**Date:** 2026-06-15
**Status:** Approved

---

## Overview

Today, AWS EBS disk rightsizing (`EbsDiskRightsizingService`) generates recommendations
(`UPGRADE_TYPE`, `DELETE_UNATTACHED`, `EXPAND`, `SHRINK`) but the user can only act on them
manually outside of XamOps, or via a "How to fix" guide modal for EXPAND/SHRINK.

This spec adds **one-click automation**: each volume row gets an action button matching its
recommendation. Clicking it runs the full remediation — including, where applicable, the
OS-level filesystem resize and ASG launch-template updates — directly from the XamOps console.

---

## 1. Access Control Model

XamOps already connects to customer AWS accounts via cross-account role assumption
(`CloudAccount.roleArn` + `externalId`, assumed via `AwsClientProvider.getCredentialsProvider`).
Accounts are onboarded with an `accessType` of `read-only` or `read-write`
(`XamOpsReadWriteRole` vs a read-only role).

- **ReadOnly accounts**: recommendations are shown as today. Action buttons are rendered but
  **disabled**, with a tooltip: *"Upgrade to ReadWrite role to enable automation"*.
- **ReadWrite accounts**: action buttons are enabled and call the automation endpoints.

The gate is enforced in the frontend only — `CloudAccount.accessType` is already returned with
account info via the existing accounts API. No new backend authorization check is required
beyond the existing IAM permission boundary (an AWS API call will fail naturally if the role
lacks permissions).

### Required IAM additions to `XamOpsReadWriteRole`

Beyond the existing read permissions (`ec2:DescribeVolumes`, `ec2:DescribeRegions`,
`ec2:DescribeInstances`, `cloudwatch:*`, `compute-optimizer:*`, `ssm:DescribeInstanceInformation`):

| Action | New permissions |
|---|---|
| UPGRADE_TYPE | `ec2:ModifyVolume`, `ec2:DescribeVolumesModifications` |
| DELETE_UNATTACHED | `ec2:DeleteVolume`, `ec2:DeleteTags` |
| EXPAND | `ec2:ModifyVolume`, `ec2:DescribeVolumesModifications`, `ssm:SendCommand`, `ssm:GetCommandInvocation` |
| SHRINK (data vol) | `ec2:CreateSnapshot`, `ec2:CreateVolume`, `ec2:AttachVolume`, `ec2:DetachVolume`, `ec2:CreateTags`, `ec2:DeleteTags`, `ssm:SendCommand`, `ssm:GetCommandInvocation` |
| ASG path (UPGRADE_TYPE/EXPAND) | `autoscaling:DescribeAutoScalingGroups`, `ec2:DescribeLaunchTemplateVersions`, `ec2:CreateLaunchTemplateVersion`, `ec2:ModifyLaunchTemplate`, `autoscaling:StartInstanceRefresh` |

These additions are documented in the existing ReadWrite role policy doc/CloudFormation
template — onboarding docs get an updated policy JSON.

---

## 2. Downtime Classification

Each action carries an explicit downtime badge, surfaced on the action button:

| Action | Downtime | Badge text |
|---|---|---|
| UPGRADE_TYPE | None — `ModifyVolume` is online | "Zero downtime" |
| DELETE_UNATTACHED | None — volume not attached | "Zero downtime" |
| EXPAND | None — `growpart`/`resize2fs`/`xfs_growfs` run on live mount | "Zero downtime" |
| SHRINK (data volume) | ~5–30s unmount/remount during final swap | "~30s disruption" |
| SHRINK (root volume) | Instance stop/start required | "Instance restart required" (v2, not automated in v1) |

---

## 3. ASG Detection & Handling

### Detection
When building each volume DTO (`buildVolumeDto`), if the volume is attached to an instance,
fetch the instance's tags (`ec2:DescribeInstances`) and check for `aws:autoscaling:groupName`.
If present, add `asgName` to the volume DTO. The frontend renders an "ASG" badge on these rows.

### Per-action ASG behavior

| Action | Non-ASG | ASG instance |
|---|---|---|
| UPGRADE_TYPE | `ModifyVolume` | Update launch template block-device-mapping volume type → set as default version → `ModifyVolume` on current instance → **trigger instance refresh** |
| EXPAND | `ModifyVolume` + SSM resize | Update launch template block-device-mapping size → set as default version → `ModifyVolume` + SSM resize on current instance → **trigger instance refresh** |
| SHRINK | SSM rsync workflow | **Blocked by default.** UI shows an explicit confirmation: *"This volume belongs to an ASG. Shrinking affects only this instance until replaced — new instances will use the original launch template size. Continue anyway?"* If confirmed, applies to the current instance only (no launch template change, since reducing capacity for new instances is out of scope for automation). |
| DELETE_UNATTACHED | Delete volume | N/A (unattached volumes aren't part of an ASG) |

### Instance refresh
After a launch template update, call `autoscaling:StartInstanceRefresh` with
`minHealthyPercentage: 90`. This performs a rolling replacement of existing ASG instances so
they pick up the new EBS configuration, while keeping ≥90% capacity online — zero downtime for
the service as a whole, assuming healthy instance replacement.

---

## 4. Per-Row Action Buttons (Frontend)

In `EbsRightsizingPage.tsx`, the recommendation column for each row currently shows a badge plus
either a "How to fix" guide button (SHRINK/EXPAND) or just reason text (UPGRADE_TYPE/
DELETE_UNATTACHED). This is replaced with:

| Recommendation | Button | Confirmation | Endpoint |
|---|---|---|---|
| `UPGRADE_TYPE` | "Upgrade to gp3" (blue) | None (zero downtime) | `POST /api/ebs/action/{accountId}` (existing) |
| `DELETE_UNATTACHED` | "Delete Volume" (red) | Yes — confirm dialog | `POST /api/ebs/action/{accountId}` (existing) |
| `EXPAND` | "Expand Now" (amber) | None (zero downtime) | `POST /api/ebs/expand/{accountId}` (new) |
| `SHRINK` (data vol) | "Shrink Now" (orange) | Yes — "~30s disruption" notice | `POST /api/ebs/shrink/{accountId}` (existing `initiateDownsize`) |
| `SHRINK` (root vol) | Disabled, "Manual guide" link | — | Opens existing guide modal (v1 unchanged) |

For ASG rows on UPGRADE_TYPE/EXPAND, an additional inline notice is shown:
*"Launch template will be updated and instances refreshed."*

For ASG rows on SHRINK, clicking shows the ASG confirmation dialog described in §3 before
calling the shrink endpoint.

### Button states
1. **Idle** — shows the action button + downtime badge
2. **Running** — button replaced with spinner + live step text (from action history polling)
3. **Success** — green "Done" check for ~3s, then row refreshes (recommendation becomes `NONE`)
4. **Failed** — red "Failed" badge with tooltip showing `errorMessage`; action button reappears
   so the user can retry

### Progress polling
After triggering an action, the frontend polls `GET /api/ebs/action-history/{accountId}` every
5 seconds, matching by `volumeId` + `status != SUCCESS/FAILED`. `EbsVolumeAction.errorMessage`
is reused as the live step-progress field while `status = IN_PROGRESS` (as already done by
`EbsShrinkExecutorService`). Polling stops once the matched action reaches `SUCCESS` or `FAILED`.

---

## 5. EXPAND Automation (New)

### New service: `EbsExpandExecutorService`
Mirrors `EbsShrinkExecutorService` (separate `@Async` bean for proxy reasons). Triggered via
`EbsDiskRightsizingService.initiateExpand(accountId, volumeId, region, instanceId, device, targetSizeGb, asgName)`.

**Non-ASG workflow:**
1. **Step 1/3** — `ec2:ModifyVolume` to `targetSizeGb`
2. **Step 2/3** — poll `ec2:DescribeVolumesModifications` until `modificationState` is
   `optimizing` or `completed` (timeout ~15 min)
3. **Step 3/3** — SSM `SendCommand` on `instanceId` running a detection + resize script:
   ```bash
   PART=$(df --output=source <mountpoint> | tail -1)
   DISK=$(lsblk -no PKNAME "$PART")
   PARTNUM=$(echo "$PART" | grep -o '[0-9]*$')
   growpart /dev/$DISK $PARTNUM
   FSTYPE=$(blkid -s TYPE -o value "$PART")
   case "$FSTYPE" in
     ext4|ext3|ext2) resize2fs "$PART" ;;
     xfs)            xfs_growfs "$PART" ;;
     *) echo "Unsupported filesystem: $FSTYPE"; exit 1 ;;
   esac
   ```
   `mountpoint` and `device` come from the volume DTO (agent-reported device/mount data, same
   source already used for SHRINK's `mountPoint`).

**ASG workflow** (when `asgName` is present):
1. **Step 1/4** — fetch the ASG's launch template, create a new version with the updated EBS
   volume size in the block device mapping, set it as the default version
2. **Step 2/4** — `ModifyVolume` on the current instance's volume (steps 1-2 from non-ASG flow)
3. **Step 3/4** — SSM filesystem resize on the current instance (step 3 from non-ASG flow)
4. **Step 4/4** — `autoscaling:StartInstanceRefresh` with `minHealthyPercentage: 90`

### New endpoint
```
POST /api/ebs/expand/{accountId}
Body: { volumeId, region, instanceId, targetSizeGb }
Response: { success: true, actionId, message: "Expand workflow initiated..." }
```
Returns immediately (action record created with `status=PENDING`), same pattern as
`/api/ebs/shrink/{accountId}`.

---

## 6. SHRINK Automation (Existing Backend, New Button)

`EbsShrinkExecutorService` (7-step SSM rsync workflow) and `initiateDownsize()` already implement
this for data volumes. No backend changes needed for the non-ASG case. The frontend "Shrink Now"
button auto-fills the request body from the volume DTO:

- `instanceId` ← `vol.instanceId`
- `mountPoint` ← resolved from agent-reported mount data for `vol.device`
- `targetSizeGb` ← `vol.recommendedSizeGb`

Root volume SHRINK remains out of scope for v1 (existing guard in `initiateDownsize` already
rejects root volumes with a message pointing to the guided workflow).

---

## 7. Summary of Changes

**Backend (new/changed):**
- `EbsExpandExecutorService` (new) — async 3-step (non-ASG) / 4-step (ASG) expand workflow
- `EbsDiskRightsizingService.initiateExpand(...)` (new) — creates `EbsVolumeAction`, delegates to executor
- `EbsDiskRightsizingService.buildVolumeDto(...)` (changed) — adds `asgName` field via instance tag lookup
- `EbsDiskRightsizingController` — new `POST /api/ebs/expand/{accountId}` endpoint
- New helper for ASG launch template update + instance refresh (shared by UPGRADE_TYPE and EXPAND ASG paths)
- `EbsDiskRightsizingService.applyAction(UPGRADE_TYPE)` (changed) — branches to ASG launch-template + instance-refresh path when `asgName` present

**Frontend (changed):**
- `EbsRightsizingPage.tsx` — replace "How to fix"/text-only recommendation cells with per-row action buttons, downtime badges, ASG badges/notices, confirmation dialogs (DELETE, SHRINK, ASG-SHRINK)
- `useEbsRightsizingData.ts` — add `applyExpand()`, ASG-aware request params, action-history polling hook for in-flight actions
- Action button disabled state + tooltip for ReadOnly accounts (`accessType` already available from account context)

**No database schema changes** — `EbsVolumeAction` already has the fields needed
(`status`, `errorMessage` for step text, `oldSizeGb`/`newSizeGb`, `estimatedSavings`).

---

## Out of Scope (v1)

- Root volume SHRINK automation (stop-instance + helper-instance workflow) — remains manual guide
- ASG launch-template changes for SHRINK (only current-instance change, with explicit warning)
- Bulk/batch "apply all" across multiple volumes — this spec is per-volume, one-click only
