package com.xammer.cloud.service.cur;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retries CUR auto-registration for connected AWS accounts that are not yet CUR-backed.
 *
 * <h2>Why this exists</h2>
 * {@link AwsAccountService#connectViaCallback} now calls
 * {@link CurAutoRegistrationService#ensureRegistered} the moment an account connects, so a
 * report that is already delivered is picked up immediately. But a brand-new account's CUR
 * report does not deliver its first file instantly — AWS writes it on its own billing
 * schedule, commonly a few hours and up to ~24h after the report is created. The connect-time
 * attempt therefore almost always runs against an empty bucket, finds no manifest, and leaves
 * the account on Cost Explorer. Nothing retried it, so every new account still needed a manual
 * "refresh from the CUR" long after the data had actually landed.
 *
 * <p>This scheduler closes that gap. It periodically re-attempts registration for every
 * CONNECTED AWS account that is still {@code COST_EXPLORER_ONLY}; whenever AWS finally delivers
 * the first CUR file, the next pass registers the account automatically — no DB edit, no admin
 * call, no button. Once an account is CUR-backed it is skipped, so the steady-state cost is one
 * cheap repository scan per interval and nothing more.
 *
 * <h2>Why a separate scheduler</h2>
 * Kept out of {@link CurAutoRegistrationService} so the registration logic stays a plain,
 * transaction-per-account service that the connect path and the admin path call directly;
 * a {@code @Scheduled} method calling a {@code @Transactional} method on the same bean would
 * also bypass the proxy and silently lose the transaction. Modelled on
 * {@code AwsInventoryScheduler}: env-guarded, overlap-guarded, and per-account failures never
 * end the pass for the others.
 */
@Service
public class CurAutoRegistrationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CurAutoRegistrationScheduler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final CurAutoRegistrationService curAutoRegistrationService;
    private final SchedulingEnvironmentConfig envConfig;

    /**
     * Off unless a profile turns it on, matching {@code xamops.inventory.enabled}. Set
     * {@code true} in uat/preprod/prod so the retry runs where accounts are actually added,
     * and leave it off for local/test where there is no real CUR to find.
     */
    @Value("${xamops.cur.auto-register.enabled:false}")
    private boolean enabled;

    /**
     * Guards against a slow pass queuing behind itself. Registration does real S3/Athena work
     * per candidate, so a pass can outrun a short interval when several accounts are pending;
     * this makes it skip a beat rather than pile up.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CurAutoRegistrationScheduler(CloudAccountRepository cloudAccountRepository,
                                        CurAutoRegistrationService curAutoRegistrationService,
                                        SchedulingEnvironmentConfig envConfig) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.curAutoRegistrationService = curAutoRegistrationService;
        this.envConfig = envConfig;
    }

    /**
     * Every 30 minutes by default, after a 5-minute startup delay. The interval only bounds how
     * long after AWS delivers the first CUR file an account waits to light up, and CUR is a daily
     * artifact, so 30 minutes is already far finer than it needs to be while staying gentle on
     * S3/Athena. A pending account's registration is attempted once per pass; an account already
     * CUR-backed is filtered out before any AWS call.
     */
    @Scheduled(fixedDelayString = "${xamops.cur.auto-register.interval-ms:1800000}",
            initialDelayString = "${xamops.cur.auto-register.initial-delay-ms:300000}")
    public void retryPendingRegistrations() {
        if (!enabled || !envConfig.isSchedulingEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[CUR] Previous auto-register pass still running — skipping this cycle");
            return;
        }

        long startMs = System.currentTimeMillis();
        int attempted = 0;
        int registered = 0;

        try {
            TenantContext.setCurrentTenant(envConfig.getCurrentTenantId());
            try {
                List<CloudAccount> candidates = cloudAccountRepository.findByProvider("AWS");
                for (CloudAccount account : candidates) {
                    if (!isPendingCurAccount(account)) {
                        continue;
                    }
                    attempted++;
                    try {
                        curAutoRegistrationService.ensureRegistered(account);
                        registered++;
                        log.info("[CUR] Auto-registered report for account {} via scheduled retry",
                                account.getAwsAccountId());
                    } catch (Exception e) {
                        // Expected and routine while AWS has not delivered the first CUR file
                        // yet. Logged at debug so a bucket that simply is not ready does not
                        // spam the log every pass; the account is retried next cycle.
                        log.debug("[CUR] Account {} still not registerable: {}",
                                account.getAwsAccountId(), e.getMessage());
                    }
                }
            } finally {
                TenantContext.clear();
            }

            if (registered > 0) {
                log.info("[CUR] Auto-register pass done in {}ms — {} newly registered of {} pending",
                        System.currentTimeMillis() - startMs, registered, attempted);
            }
        } catch (Exception e) {
            log.error("[CUR] Auto-register pass aborted: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * A connected AWS account that is still served by Cost Explorer and has a role to assume.
     *
     * <p>An account already CUR-backed ({@code aws_billing_source} usesCur) is skipped so the
     * pass does no AWS work for the steady-state majority. {@code ensureRegistered} itself only
     * ever looks at the conventional {@code xamops-cur-<id>} report, so a member account under a
     * payer (which keeps no own report) simply fails the manifest read every pass and stays on
     * Cost Explorer — the same safe no-op as before, just retried cheaply.
     */
    private boolean isPendingCurAccount(CloudAccount account) {
        return "AWS".equals(account.getProvider())
                && account.getAwsAccountId() != null
                && !account.getAwsAccountId().isBlank()
                && "CONNECTED".equals(account.getStatus())
                && account.getRoleArn() != null
                && !account.resolveAwsBillingSource().usesCur();
    }
}
