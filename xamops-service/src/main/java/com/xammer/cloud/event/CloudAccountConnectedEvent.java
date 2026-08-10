package com.xammer.cloud.event;

/**
 * Published when a cloud account first reaches CONNECTED.
 *
 * <p>
 * Carries the primary key rather than the {@link com.xammer.cloud.domain.CloudAccount}
 * entity on purpose. Listeners run on other threads with no open persistence session,
 * and {@code CloudAccount.client} is a LAZY {@code @ManyToOne} — handing over the
 * detached entity would throw LazyInitializationException the moment a listener
 * resolved the owning tenant's email. The id forces a re-query where the session is.
 *
 * <p>
 * An event rather than a direct call so onboarding does not depend on WAR, billing, or
 * whatever else wants to react to a new account later.
 *
 * @param cloudAccountId primary key of the newly connected account
 * @param provider       AWS | GCP | Azure, for listener-side filtering without a lookup
 * @param reconnected    true when the account had previously failed and was retried,
 *                       false on a genuinely first connection
 */
public record CloudAccountConnectedEvent(
        Long cloudAccountId,
        String provider,
        boolean reconnected) {
}
