# Runbook: AutoSpotting legacy groups, adopt or revert
Operator runbook for a group that was converted before XamOps tracked it (design-core.md §2.5, §7.4;
design-aws.md §5.3, §5.4; decisions PO-12, PO-13). Covers the state LEGACY_UNCONFIRMED ("Converted before
tracking"), the reason `ORPHAN_MARKER`, the choice between adopting and reverting, and the Phase A disable
that stays the only way back until the AWS revert is switched on (B5, Phase 23). Nothing here bills.
A legacy group is never billable and nothing acts on it automatically.
Audience: XamOps platform operators and client owners. Revert and adopt both need the read-write connection.
## 1. Trigger
- A group shows "Converted before tracking" (LEGACY_UNCONFIRMED) in the AutoSpotting list.
- A group is LEGACY_UNCONFIRMED with the reason `ORPHAN_MARKER`.
- A customer asks to take such a group back to On-Demand, or to have XamOps manage it.
## 2. What the state means
Discovery creates the row when it sees a group tagged `spot-enabled=true`, or a group tagged
`xamops:autospotting:group-id=<uuid>` with no row for that uuid. XamOps did not take the original
configuration, so the stored snapshot is reconstructed and always `exact=false`.
| Reason | Meaning | Allowed plans |
|---|---|---|
| (none) | Tagged `spot-enabled=true` by the older code | Adopt, Revert |
| `ORPHAN_MARKER` | A XamOps marker names a group id that has no row | Revert only. Adopt is refused, because the marker may belong to another account or an earlier row |
## 3. Adopt or revert
| Choice | Use it when | What happens |
|---|---|---|
| Adopt | The group should stay on Spot and become managed | Takes a snapshot, writes the marker, removes `spot-enabled`, and the group becomes ACTIVE under a profile |
| Revert | The group should go back to On-Demand | The REVERT program below, with a reconstructed target and an acknowledgement |
Revert target of a legacy group (design-aws.md §5.4, last row):
- If the policy has a single instance type and no On-Demand base, the group goes back to one standalone
  launch template.
- Otherwise the policy stays at base 0 and On-Demand share 100.
Either way the plan says the restore is not exact (`LEGACY_REVERT_TARGET`), and the customer confirms it.
Spot is drained first (Auto Scaling launches each On-Demand replacement before it retires a Spot instance),
then the shape is restored, then the marker and `spot-enabled` are removed.
## 4. The revert program, for any managed group
Steps: check preconditions, drain Spot, wait until none remains ("Spot instances remaining: n", checked every
minute), restore the shape, verify, remove the marker. The group enters REVERTING with the first change.
- A revert is never blocked by eligibility, profile or desired-size checks. A group at desired 1 can be reverted.
- It is held only by the emergency freeze (`AS_FROZEN`) or an unreadable switch store (`AS_SWITCH_UNREADABLE`).
- Permissions are checked first. When `iam:PassRole` for the launch template's instance profile is missing,
  the plan shows `REVERT_NEEDS_PASSROLE` with "Update stack" and offers a drain-only revert, marked not exact
  (`EXACT_RESTORE_IMPOSSIBLE`, reason `PASSROLE_DENIED`). Drain only keeps the mixed policy at 100% On-Demand and
  the group ends REVERTED. After the customer updates the stack, a new Revert plan is offered on that REVERTED
  group (it is offered only after a drain-only revert), and it restores the exact original with no drain.
- A launch template changed outside XamOps is kept, as are the customer's own instance types and any field
  accepted as the baseline. Spot settings always come from the snapshot.
- If the original launch template version was deleted, the plan proposes the live template at `$Default` and
  needs an acknowledgement (`TEMPLATE_VERSION_DELETED`).
## 5. Diagnosis
- A revert that fails in its first step (stale plan, missing permission, missing snapshot) ends FAILED with no
  change made. The group keeps its state and billing continues as before. Plan the revert again.
- A revert that fails after the first change leaves the group FAILED. Open `stuck-operation.md`.
- Step output shows `exact`, the reasons, and for a drain-only revert the note `DR-3` (the group keeps the
  XamOps policy until the exact revert).
## 6. The Phase A disable until B5
Until `autospotting.aws.v2-mutations-enabled` is turned on for AWS (Phase 23), the older disable endpoint is the
only way back for a LEGACY_UNCONFIRMED group or a group with no row. It takes the group lease for the call, so it
cannot race a v2 operation. For a group in any other v2 state it returns 409 `PLAN_REQUIRED`. Once the flag is on
it returns `PLAN_REQUIRED` for every AWS group, and the Revert flow replaces it.
## 7. Do not
- Do not edit the stored snapshot or the marker tag by hand.
- Do not remove `spot-enabled` yourself on an ORPHAN_MARKER group; revert it.
