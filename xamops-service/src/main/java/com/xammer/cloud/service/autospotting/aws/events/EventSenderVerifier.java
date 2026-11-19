package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The sender check (design-aws.md §9.5, AC-EVT-02). For an IAM role the SQS {@code SenderId} is the role's
 * unique id, then {@code :} and the session name. It carries no account number, so the role id part is
 * compared with {@code cloud_account.as_forwarder_role_id}, stored from the stack output
 * {@code AutoSpottingForwarderRoleId} by the coverage job.
 *
 * <ul>
 *   <li>A stored id that equals the sender's: {@link Verdict#OK}.</li>
 *   <li>A stored id that differs, or no sender id at all: {@link Verdict#MISMATCH}. The caller drops and
 *       counts the message; it never falls back to a lookup, so a spoofer cannot talk its way in.</li>
 *   <li>No stored id: one {@code iam:GetRole} lookup of {@code xamops-as-events-fwd-role{suffix}} through
 *       the customer's read role, stored when found. A role that cannot be read or does not exist is
 *       {@link Verdict#RETRY}: the message is not trusted and not deleted.</li>
 * </ul>
 * The legacy role name is never looked up, so a removed legacy role cannot send every message to the DLQ.
 */
@Component
public class EventSenderVerifier {

    private static final Logger log = LoggerFactory.getLogger(EventSenderVerifier.class);

    public enum Verdict { OK, MISMATCH, RETRY }

    private final ForwarderRoleLookup lookup;
    private final AwsEventStore store;
    private final AwsEventProperties properties;

    public EventSenderVerifier(ForwarderRoleLookup lookup, AwsEventStore store, AwsEventProperties properties) {
        this.lookup = lookup;
        this.store = store;
        this.properties = properties;
    }

    /** The role id part of an SQS {@code SenderId}, or null. */
    static String roleIdOf(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return null;
        }
        int colon = senderId.indexOf(':');
        return colon < 0 ? senderId : senderId.substring(0, colon);
    }

    public Verdict verify(CloudAccount account, String senderId, String region) {
        String sender = roleIdOf(senderId);
        if (sender == null) {
            return Verdict.MISMATCH;
        }
        String stored = account.getAsForwarderRoleId();
        if (stored != null && !stored.isBlank()) {
            return stored.equals(sender) ? Verdict.OK : Verdict.MISMATCH;
        }
        Optional<String> suffix = properties.suffix();
        if (suffix.isEmpty()) {
            return Verdict.RETRY;
        }
        Optional<String> found;
        try {
            found = lookup.roleId(account, region == null || region.isBlank() ? "us-east-1" : region, suffix.get());
        } catch (RuntimeException e) {
            log.warn("forwarder role lookup failed for account {}: {}", account.getId(), e.toString());
            return Verdict.RETRY;
        }
        if (found.isEmpty() || found.get().isBlank()) {
            return Verdict.RETRY;
        }
        store.storeForwarderRoleId(account.getId(), found.get());
        account.setAsForwarderRoleId(found.get());
        return found.get().equals(sender) ? Verdict.OK : Verdict.MISMATCH;
    }
}
