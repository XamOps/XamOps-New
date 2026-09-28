package com.xammer.cloud.dto.matters;

/**
 * A client as the DSPM admin's picker needs it: enough to choose one, and nothing else.
 *
 * Exists because the obvious alternative is expensive. {@code GET /api/xamops/billops-admin/clients}
 * fans out to Cost Explorer, GCP and Azure cost APIs and the Upwind tables to build its rows — sensible
 * for the Accounts screen, absurd for populating a dropdown. This is two queries.
 *
 * @param entitled whether the client holds the add-on.
 * @param mapped   whether a mapping already exists, so the picker can separate "add a mapping" from
 *                 "edit the existing one" rather than letting an operator discover the difference by
 *                 overwriting something.
 */
public record DspmClientOption(
        Long clientId,
        String clientName,
        boolean entitled,
        boolean mapped
) {
}
