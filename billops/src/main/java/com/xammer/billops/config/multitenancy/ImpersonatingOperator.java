package com.xammer.billops.config.multitenancy;

import java.io.Serializable;

/**
 * The admin behind a "Login As" token, as the token's actor claims name them.
 *
 * <p>{@link ImpersonationJwtFilter} sets it as the details of every Authentication it builds, and only
 * after the token's signature and issuer have been verified, so its values can only come from a token
 * billops itself signed. When such a session mints another Login-As token,
 * {@code ImpersonationTokenController} forwards these values instead of the session's principal, which is
 * the account being worn. A nested token therefore names the admin who started the chain.
 *
 * <p>Each field is null when the token does not carry the claim: tokens minted before
 * {@code actorUsername} and {@code actorRole} existed carry only {@code actorUserId}, and the oldest carry
 * none.
 *
 * @param userId   the {@code actorUserId} claim
 * @param username the {@code actorUsername} claim
 * @param role     the {@code actorRole} claim
 */
public record ImpersonatingOperator(Long userId, String username, String role) implements Serializable {
}
