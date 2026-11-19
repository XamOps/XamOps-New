package com.xammer.cloud.service;

/**
 * Which opt-out a recipient lookup honours (design-ux-ops.md section 7.3).
 *
 * <ul>
 *   <li>{@link #SECURITY}: the security-mail switch ({@code User.emailNotificationsEnabled}). This is what
 *       {@link NotificationRecipientResolver#emailsForAccount(com.xammer.cloud.domain.CloudAccount)} has always
 *       done, so every existing caller is unchanged.</li>
 *   <li>{@link #AUTOSPOTTING}: the AutoSpotting switch ({@code User.autospottingEmailEnabled}) only. A security
 *       mute does not silence it, and the AutoSpotting mute does not silence security mail.</li>
 *   <li>{@link #AUTOSPOTTING_MANDATORY}: neither switch. Used for notices PO-24 says can't be opted out of
 *       (failures), so a mute of either kind can never hide an apply failure.</li>
 * </ul>
 */
public enum NotificationCategory {
    SECURITY,
    AUTOSPOTTING,
    AUTOSPOTTING_MANDATORY
}
