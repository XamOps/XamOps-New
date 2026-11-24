# Runbook: AWS event DLQ replay

Scope: the AutoSpotting AWS events queue `xamops-autospotting-events{suffix}.fifo` and its dead-letter queue `xamops-autospotting-events{suffix}-dlq.fifo` (suffix empty in prod, `-uat` in UAT). Stack: `cloudformation/xamops-autospotting-queue.yaml`. Design: `design-aws.md` section 9.8.

Events are hints. Every decision comes from the reconciler's polling, so a message in the DLQ never loses a conversion, fallback or ledger entry. Replaying only restores analytics rows and, once record-only mode ends (Phase 23), an earlier reconcile.

## When this fires

- Alarm `autospotting-events-dlq-depth-{env}`: a message is in the DLQ for 5 minutes.
- A message reaches the DLQ after 5 receives without being acknowledged. Causes, in order of likelihood:
  1. The body is not a forwarder envelope (unparseable). It will fail again on replay.
  2. The sender could not be verified: the account has no stored forwarder role id and `iam:GetRole` on `xamops-as-events-fwd-role{suffix}` failed (read role broken, or the stack predates rw-2026.11.1).
  3. A database or AWS read failure during processing.

## Look first

1. `/autospotting-admin`, tab **DLQ**. It shows the depth and, for up to ten messages, the detail type, the customer account, the send time and the receive count. Message bodies are never shown.
2. In the logs, search for `AutoSpotting event left for retry` and `forwarder role lookup failed` around the send time.
3. For cause 2, run the coverage job for the account (it stores `AutoSpottingForwarderRoleId` from the stack outputs) or ask the customer to update the stack.

## Replay

1. Fix the cause first, or the messages return to the DLQ after 5 more receives.
2. On the DLQ tab, enter a reason and click **Replay**. The API is `POST /api/xamops/autospotting-admin/events/dlq/redrive?provider=aws` with `{"reason": "..."}`, the intent header and an `Idempotency-Key`. Super admin or XamOps admin only; impersonated sessions are refused. Every replay writes an `OPERATOR_EVENTS_DLQ` audit record with the reason.
3. One replay at a time per queue. A second request while one runs returns 409 `REDRIVE_IN_PROGRESS`.
4. Mode `MOVE_TASK`: SQS `StartMessageMoveTask` moves the messages back to the source queue. Mode `RESEND`: used when SQS refuses move tasks for this FIFO pair (V-AWS-14); the service receives each message, re-sends it with its original message group and a fresh deduplication id, then deletes it from the DLQ.

Replays are safe. The inbox (`autospotting_event_inbox`, keyed by the EventBridge event id) turns an event that was already applied into a duplicate, so nothing is recorded twice.

## Do not

- Do not purge the DLQ to silence the alarm. Messages expire after 14 days on their own.
- Do not point a non-prod environment at the prod queue to inspect it. The listener, the policy sync and the DLQ endpoints all refuse a production queue outside the prod profile.
- Do not edit the queue policy by hand. `AutoSpottingQueueAuthorizer` rewrites it from the connected accounts within an hour.

## Verify

- DLQ depth returns to 0 and the alarm returns to OK.
- `autospotting.events{outcome=applied|duplicate}` rises by the number of replayed messages.
