package com.xammer.cloud.dto.security;

import com.xammer.cloud.dto.DashboardData;

/**
 * The Security Center's Identity &amp; Access payload, and nothing else.
 *
 * <p>
 * Exists because that tab used to read its identities out of the full dashboard aggregate
 * ({@code GET /api/xamops/dashboard/data}), which joins ~18 collectors — Cost Explorer
 * history, rightsizing, waste detection, reservation recommendations, service quotas,
 * month-to-date spend — before it returns. Rendering one IAM list therefore waited on all
 * of them, and when the slowest exceeded the gateway's read timeout the browser got an
 * nginx HTML 504 instead of data. The identities themselves were never the problem.
 *
 * <p>
 * The three context fields are carried alongside the inventory because the canvas needs
 * them to draw the account node and to label identities per provider. Reading them off the
 * dashboard response is what coupled this tab to that endpoint in the first place, so they
 * travel with the thing that actually needs them.
 *
 * @param provider    AWS, GCP or Azure — decides the wording for "users" vs "principals"
 * @param accountId   provider account identifier, echoed back for the account node
 * @param accountName display name for the account node
 * @param iamDetails  the identity inventory, including per-identity risk when assessed
 */
public record IdentityInventoryResponse(
        String provider,
        String accountId,
        String accountName,
        DashboardData.IamDetail iamDetails) {
}
