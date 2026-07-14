# Chatbot Feature/Tab Auto-Answer — Design

**Date:** 2026-07-14
**Status:** Approved, ready for implementation plan

## Problem

The support chat widget (`SupportChatWidget.tsx`) already tells users: *"Ask me anything about your cloud resources, billing, or XamOps features."* In reality, every message sent through it is only saved and silently forwarded to a human support queue:

- `ChatbotController.chat()` (`xamops-service/src/main/java/com/xammer/cloud/controller/ChatbotController.java`) calls `SupportChatService.sendUserMessage()` and returns a static acknowledgement ("Request received. An agent will be with you shortly.").
- `AnythingLlmService` — a dormant integration wired to a real AnythingLLM workspace (`anythingllm.url` / `anythingllm.api.key` in `application*.properties`, pointing at `xamops-workspace`) — has its actual call logic commented out and is never invoked anywhere in the codebase. It's effectively dead code today.

There is no automated reply of any kind. This design adds one: when a user asks something that matches a known XamOps feature or sidebar tab, the bot answers instantly, without waiting on a human.

## Decision Summary

These were confirmed with the user during brainstorming:

1. **Answer source:** in-house deterministic keyword/FAQ matching — no external AI call, no re-enabling `AnythingLlmService`.
2. **Human handover behavior:** auto-answer the matched question, then nudge the user to ask again or say more if it didn't help. No explicit "yes/no" state machine — an unmatched follow-up naturally falls through to the existing human-escalation path.
3. **Knowledge base storage:** hardcoded Java class (`FeatureFaqCatalog.java`), mirroring the pattern of `frontend-app/src/constants/xamopsTabs.ts` — not a JSON resource file, not a DB table.
4. **Coverage:** all ~40 tab keys across the 9 modules in `XAMOPS_MODULES` (`xamopsTabs.ts`) — not just the 9 top-level modules.
5. **BillOps admin visibility:** auto-answered questions are still persisted (full audit trail, chat history survives reload) but are marked so they do **not** count toward the BillOps admin "unread / needs attention" badge.

## Architecture

### Request flow

```
User sends message
      │
      ▼
POST /api/ai/chat  (ChatbotController.chat) — unchanged endpoint
      │
      ▼
FeatureFaqMatcher.match(message)
      │
      ├── MATCHED ──────────────────────────────────────────────┐
      │                                                          │
      │  1. Persist the user's question as a SupportMessage      │
      │     with status="READ" (not the default "UNREAD")        │
      │  2. Persist the bot's answer as a SupportMessage          │
      │     (isUserMessage=false, status="DELIVERED",             │
      │      sender = same User as the question)                  │
      │  3. Return ChatResponse (text unused by frontend today,   │
      │     kept for API shape compatibility)                     │
      │                                                          │
      └── UNMATCHED ─────────────────────────────────────────────┤
                                                                  │
         Identical to current behavior:                          │
         SupportChatService.sendUserMessage() (status="UNREAD")  │
         → static ack response → human support queue             │
                                                                  ▼
                                              Frontend: sendMessage() already
                                              calls fetchHistory() right after
                                              POST resolves, which pulls both
                                              new rows (question + bot answer,
                                              or just the question) in one
                                              request. No frontend changes.
```

### Why no frontend changes are needed

`SupportChatWidget.tsx` already renders any message with `isUserMessage=false` using the bot avatar/bubble styling — it doesn't distinguish "human admin" from "automated reply." `useSupportChat.ts`'s `sendMessage()` already calls `fetchHistory()` immediately after the POST resolves, so a synchronously-persisted bot reply shows up on the very next fetch. The existing unread-badge and read-receipt plumbing (`getUnreadAdminMessageCount`, `markAdminMessagesAsRead`, `POST /api/ai/chat/mark-read`) already operates on `isUserMessage=false && status="DELIVERED"` and works unmodified for bot messages.

### Why no conversational state is needed

The bot's nudge text ("Did this answer your question? If not, just send another message and I'll loop in our support team.") is just appended to the answer. If the user's next message is a real follow-up ("no", "still confused", a different question), it independently goes through the same matcher: if it doesn't match anything, it falls through to the existing human-escalation path exactly as an unmatched message does today. No session flags, no pending-state tracking.

## Data Model

`xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqCatalog.java` (new):

```java
public record FeatureFaqEntry(
    String tabKey,        // matches xamopsTabs.ts key, e.g. "finops-disk-rightsizing"
    String moduleLabel,   // e.g. "FinOps"
    String tabLabel,      // e.g. "Disk Rightsizing"
    List<String> keywords,
    String description    // 1-3 sentences, written for end users
) {}

public final class FeatureFaqCatalog {
    public static final List<FeatureFaqEntry> ENTRIES = List.of(
        new FeatureFaqEntry(
            "finops-disk-rightsizing", "FinOps", "Disk Rightsizing",
            List.of("disk", "ebs", "volume", "rightsizing", "resize", "expand", "shrink"),
            "Disk Rightsizing analyzes your EBS, Persistent Disk, and Managed Disk volumes for " +
            "over- or under-provisioning, and lets you resize, expand, shrink, or delete them " +
            "with zero downtime."
        ),
        // ... one entry per tab key in XAMOPS_MODULES (~40 total)
    );
}
```

**Content sourcing (implementation task, not invented here):** descriptions will be written by reading each page's own header/subtitle copy in `frontend-app/src/pages/`, cross-referenced against the marketing site's `platform/*.jsx` pages where a feature overlaps (e.g. Disk Rightsizing, Spot Automation, SecOps, Cost Analytics, SRE, DBOps already have on-brand copy there). Any tab whose actual behavior can't be confidently determined from its page/controller code will be flagged for your review rather than guessed at.

## Matching Algorithm

`FeatureFaqMatcher` (new, plain Java, no external dependency):

1. Normalize the incoming message: lowercase, strip punctuation, split on whitespace.
2. For each `FeatureFaqEntry`, compute a score:
   - `+3` if `tabLabel` (case-insensitive) appears as a substring of the normalized message.
   - `+1` for each keyword token present in the message.
3. Take the entry with the highest score, provided:
   - the score is `>= 2`, and
   - there is no tie for the top score (a tie is treated as **no match** — escalating to a human is safer than guessing between two features).
4. Return `Optional<FeatureFaqEntry>`.

This is intentionally conservative: a generic word like "cost" alone (1 point) won't fire; "what does disk rightsizing do" (label match, 3 points, plus keyword hits) will.

## Persistence / Status Semantics

| Scenario | User's question `status` | Bot reply created? | Bot reply `status` | Counts toward BillOps unread badge? |
|---|---|---|---|---|
| Matched | `"READ"` (was `"UNREAD"` default) | Yes | `"DELIVERED"` | No — `BillOpsSupportController.getInbox()` only counts user messages with `status="UNREAD"` |
| Unmatched | `"UNREAD"` (unchanged) | No | — | Yes, same as today |

Implementation note: `SupportChatService.sendUserMessage()` is left untouched (still used for the unmatched path). Two new small methods are added instead — `recordAutoResolvedQuestion(...)` and `sendBotAnswer(...)` — so the existing, widely-relied-on method's behavior and signature don't change.

## Testing Plan

- Unit tests for `FeatureFaqMatcher`: label-phrase match, keyword-only match, below-threshold no-match, tie-breaking no-match, punctuation/case normalization.
- Unit tests for `ChatbotController`/`SupportChatService`: matched path persists two messages with correct statuses and `sender`; unmatched path is byte-for-byte identical to current behavior (regression guard).
- Manual verification: open the widget, ask a known feature question, confirm instant bot reply with correct styling and no ticket/unread badge appears on a BillOps admin test account; ask an unrelated question, confirm existing human-escalation flow is untouched.

## Out of Scope

- Re-enabling `AnythingLlmService` / calling out to AnythingLLM.
- A BillOps admin UI for editing the FAQ catalog (it ships as code, redeployed like everything else in this repo).
- Any conversational state machine beyond "next message either matches or escalates."
- Rewriting `AnythingLlmService`'s dead/commented code (left as-is; out of scope for this change).
