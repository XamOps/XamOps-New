# Runbook: AutoSpotting allowlist, contracts and go-live
Operator runbook for letting an account convert groups while allowlist-only is on, for the contract
that decides whether its groups can ever bill, and for the go-live instant (design-core.md §6.1, §7.3;
design-ledger.md §2.3, §11.4; design-ux-ops.md §2.5, §10.5; decision DC-11, PO-16). Nothing here bills.
Billing stays off until Phase 35, and a contract or go-live row is a record only.

Audience: allowlist changes, XamOps platform operators (super admin or XamOps admin). Contracts and
go-live, BillOps admins (ROLE_BILLOPS_ADMIN) or super admins. Login-As (impersonated) sessions can read
but never write any of these. The OTP-exempt demo accounts are denied.

## 1. Trigger
- An account is to join the conversion allowlist before `conversions-allowlist-only` is lifted for it.
- A client signs, or ends, an AutoSpotting contract.
- The first conversion flag of a cloud is lifted and the ledger writer starts (go-live is set at that moment, once).
- An account leaves the pilot.

## 2. How the basis works
An account joins the allowlist only with a recorded billing basis.

| Basis (screen label) | Accepted when | What it records | Effect on a plan's `feeBasis` |
|---|---|---|---|
| `CONTRACT` ("Client contract") | The account's client has a contract in force right now | The contract id | The contract in force: `{contractId, sharePct, monthlyCap}` |
| `NO_FEE` ("Never bills") | A reason is given | No contract | `{contractId: null, reason: NO_FEE_ACCOUNT}`: no AutoSpotting fee applies, even if the client has a contract |
| anything else, or `CONTRACT` without a contract in force, or `NO_FEE` without a reason | never | nothing | 409 `ALLOWLIST_NEEDS_BILLING_BASIS` |

A group enabled while its client has no contract never bills, even after a contract is signed, unless
it is reverted and re-enabled (PO-16). The recorded `feeBasis` of the plan that enabled the group stays
with it. A contract that ends later does not remove the allowlist row: the list shows "Ended" and plans
confirmed after that say "No AutoSpotting fee applies to this group". Savings are still measured for a
`NO_FEE` account; they are stored as not billable.

## 3. Diagnosis
Allowlist (operator API, `/api/xamops/autospotting-admin`):
- `GET /allowlist` lists each row: cloud, basis, contract id, whether the contract is still in force, reason, who added it.
- `GET /allowlist/options/{cloudAccountId}` says whether "Client contract" can be offered for that account.

Contracts and go-live (BillOps admin API, `/api/xamops/billops-admin/autospotting`):
- `GET /clients/{clientId}/contracts` lists every contract of the client, newest first, with `inForce`.
- `GET /golive` lists the go-live instant per cloud.

Screens: Allowlist tab on `/autospotting-admin`; Contracts and Go-live tabs on `/billops-admin-autospotting`.

Direct database read (read-only, for support):
```sql
SELECT id, client_id, share_pct, monthly_cap, declared_discount_pct, effective_from, effective_to
  FROM autospotting_contract WHERE client_id = :clientId ORDER BY effective_from DESC;
SELECT * FROM savings_ledger_golive;
```

## 4. Actions
### 4.1 Add an account to the allowlist
1. On `/autospotting-admin`, open the Allowlist tab, enter the account id, choose Find.
2. If the client has a contract in force the screen offers "Client contract" and "Never bills". Otherwise only "Never bills".
3. Choose the basis, enter the reason (1 to 500 characters), choose "Add to allowlist".
4. Confirm the row shows the right basis and, for "Client contract", "In force".

API: `POST /allowlist {cloudAccountId, billingBasis, reason}` with `X-XamOps-Intent: autospotting` and an `Idempotency-Key`.
Adding an account that is already listed changes its basis, and the check runs again.

### 4.2 Choose the basis
- The client has signed and the contract is in force: "Client contract".
- The account is XamOps's own, a sandbox, or a pilot that must never be invoiced: "Never bills", with the reason spelled out (for example "xamops GCP test project").
- The client signs later: leave the account on "Never bills" for the groups already converted. Switch the row to "Client contract" only for groups enabled afterwards, and tell the client the earlier groups stay non-billable (PO-16).

### 4.3 Remove an account
Allowlist tab, Remove, enter a reason. `DELETE /allowlist/{cloudAccountId}` with `{reason}`. Groups already converted are unaffected.

### 4.4 Create a contract
1. `/billops-admin-autospotting`, Contracts tab, enter the client id, Load.
2. Fill share %, monthly cap in USD (blank for no cap), and the discount. The discount has no preselection: choose "Discount declared" with a percentage (0 means none) or "Not declared". "Not declared" is stored as null, never 0.
3. Leave From blank for now, or give a future instant. A date in the past is refused. Contracts of one client never overlap.
4. Enter a reason, choose "Create contract".

### 4.5 End a contract
Contracts tab, End contract, optionally an end instant (blank means now, never in the past), a reason. A contract can only be ended earlier. Its terms are never edited: a change of terms is a new contract from a later month start.

### 4.6 Set go-live
Go-live tab, "Set AWS go-live" or "Set GCP go-live", read the confirmation, enter a reason, confirm. It is set once. A second attempt returns 409 `GOLIVE_ALREADY_SET`. An instant in the past is refused: nothing is back-filled.

## 5. Verification
- After adding: `GET /allowlist` shows the row. Create a plan for a group on that account: the plan's `feeBasis` is the contract (for "Client contract") or `{contractId:null, reason:NO_FEE_ACCOUNT}` (for "Never bills"), and the plan dialog says "No AutoSpotting fee applies to this group" for the second.
- After a contract change: `GET /clients/{id}/contracts` shows it and the state is right.
- Every change has an audit row: `OPERATOR_ALLOWLIST` for the allowlist, `BILLOPS_ADMIN` for contracts and go-live.

## 6. Rollback
- A wrong allowlist row: add the account again with the right basis (it replaces the row), or remove it.
- A wrong contract: end it (earlier only) and create the correct one from a later instant. It cannot be edited or deleted.
- A wrong go-live: it cannot be changed. Stop and escalate (section 7).

## 7. Escalation
Go-live set wrongly, a contract that must change for a month already closed, or an unexpected 409 after a retry: BillOps lead and the platform on-call. Do not edit `autospotting_contract` or `savings_ledger_golive` by hand; the database refuses it, and a privileged change needs the retention path in design-core.md §8.
