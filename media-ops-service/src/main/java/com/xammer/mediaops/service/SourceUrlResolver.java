package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the URL FFmpeg should read a job's source from, at the moment the job runs.
 *
 * <p>Why this exists: a job's {@code sourceUrl} is usually a presigned S3 link, and a
 * presigned link is a point-in-time artifact. It stops working when its signature expires
 * and — because the S3 browser signs with STS session credentials — also when that session
 * ends, whichever comes first. The URL was previously minted once at submit time and stored
 * on the job row, so anything that did not run promptly replayed a dead link:
 *
 * <ul>
 *   <li>a job queued behind others (the transcode pool runs few jobs at a time, so a large
 *       submission drains over hours),</li>
 *   <li>a job scheduled for later,</li>
 *   <li>a job retried after it failed — which replayed the <em>same</em> dead URL, so
 *       retrying could never do anything but fail again.</li>
 * </ul>
 *
 * All three surfaced identically as {@code FFmpeg exit code 1: HTTP error 403 Forbidden},
 * which reads like a permissions problem and is really an expiry problem.
 *
 * <p>Re-signing per run makes the URL's lifetime irrelevant: it only has to outlive the one
 * job about to use it. Sources that cannot be re-signed (a pasted URL, a local upload, or a
 * row created before the source location was recorded) fall back to the stored URL, and if
 * that URL is a presigned link whose expiry has already passed, the job fails with a message
 * that says so instead of a raw 403.
 */
@Service
public class SourceUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(SourceUrlResolver.class);

    /** SigV4 stamps requests as {@code 20261009T085447Z}. */
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern AMZ_DATE_PARAM = Pattern.compile("[?&]X-Amz-Date=([^&]+)");
    private static final Pattern AMZ_EXPIRES_PARAM = Pattern.compile("[?&]X-Amz-Expires=(\\d+)");

    private final UserS3Service userS3Service;
    private final AccountS3Service accountS3Service;

    public SourceUrlResolver(UserS3Service userS3Service, AccountS3Service accountS3Service) {
        this.userS3Service = userS3Service;
        this.accountS3Service = accountS3Service;
    }

    /**
     * Thrown when no usable source URL can be produced. Carries a message meant for the
     * customer, since it lands in the job's {@code errorMessage} and on the jobs table.
     */
    public static class SourceUnavailableException extends RuntimeException {
        public SourceUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Resolve a usable input URL for {@code job}, re-signing when the source location is
     * known.
     *
     * @return a URL valid now, which may be the job's existing {@code sourceUrl}
     * @throws SourceUnavailableException when the source cannot be reached or re-signed
     */
    public String resolve(TranscodeJob job) {
        String key = job.getSourceKey();

        // A bucket in the customer's connected AWS account, read via its onboarding role.
        if (key != null && !key.isBlank()
                && job.getSourceAccountId() != null && !job.getSourceAccountId().isBlank()
                && job.getSourceBucket() != null && !job.getSourceBucket().isBlank()) {
            try {
                String url = accountS3Service.presignSource(job.getSourceAccountId(), job.getClientId(),
                        job.getSourceBucket(), job.getSourceRegion(), key);
                log.info("[MediaOps] Job {} source re-signed from s3://{}/{}",
                        job.getId(), job.getSourceBucket(), key);
                return url;
            } catch (Exception e) {
                throw new SourceUnavailableException("Could not read the source from s3://"
                        + job.getSourceBucket() + "/" + key + " — " + e.getMessage());
            }
        }

        // A key-based connected bucket (stored access keys).
        if (key != null && !key.isBlank() && job.getSourceS3ConfigId() != null) {
            try {
                String url = userS3Service.presignUrl(job.getSourceS3ConfigId(), job.getClientId(),
                        key, (int) Duration.ofHours(12).toMinutes());
                log.info("[MediaOps] Job {} source re-signed from connected bucket {} key {}",
                        job.getId(), job.getSourceS3ConfigId(), key);
                return url;
            } catch (Exception e) {
                throw new SourceUnavailableException("Could not read the source '" + key
                        + "' from the connected S3 bucket — " + e.getMessage());
            }
        }

        String stored = job.getSourceUrl();
        if (stored == null || stored.isBlank()) {
            throw new SourceUnavailableException("This job has no source file.");
        }

        // Rows created before the source location was recorded still carry enough to
        // recover it: a presigned URL names its bucket and region in the hostname, and
        // the key is already on the row. Only the account is missing, so find the one
        // that can read the object. Without this, every job that failed before this fix
        // would have to be re-submitted by hand rather than simply retried.
        S3Location recovered = key != null && !key.isBlank() ? parseS3Location(stored) : null;
        if (recovered != null) {
            String accountId = accountS3Service.findAccountForObject(
                    job.getClientId(), recovered.bucket(), recovered.region(), key);
            if (accountId != null) {
                // Remember it so the next run goes straight to signing. The caller saves
                // the job once this returns.
                job.setSourceAccountId(accountId);
                job.setSourceBucket(recovered.bucket());
                job.setSourceRegion(recovered.region());
                log.info("[MediaOps] Job {} source located in account {} at s3://{}/{}",
                        job.getId(), accountId, recovered.bucket(), key);
                try {
                    return accountS3Service.presignSource(accountId, job.getClientId(),
                            recovered.bucket(), recovered.region(), key);
                } catch (Exception e) {
                    throw new SourceUnavailableException("Could not read the source from s3://"
                            + recovered.bucket() + "/" + key + " — " + e.getMessage());
                }
            }
        }

        // Nothing to re-sign: a pasted URL, a local upload, or a source whose bucket none
        // of the connected accounts can read any more.
        Instant expiredAt = presignedExpiry(stored);
        if (expiredAt != null && expiredAt.isBefore(Instant.now())) {
            throw new SourceUnavailableException(
                    "The source link for this job expired on " + expiredAt
                    + " and could not be renewed — XamOps could not find a connected AWS"
                    + " account with read access to the source file. Check the account is"
                    + " still connected and its role can read the bucket, then retry.");
        }
        return stored;
    }

    /** A bucket and region read back out of an S3 URL. */
    private record S3Location(String bucket, String region) {}

    /**
     * Virtual-hosted-style {@code bucket.s3.region.amazonaws.com} and path-style
     * {@code s3.region.amazonaws.com/bucket/...}, which is what the S3 browser and the
     * AWS SDK produce. Returns null for anything else — a non-S3 URL has no bucket to find.
     */
    private static S3Location parseS3Location(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null || !host.contains("amazonaws.com")) return null;
            String[] parts = host.split("\\.");
            int s3 = -1;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("s3") || parts[i].startsWith("s3-")) { s3 = i; break; }
            }
            if (s3 < 0) return null;
            // The label after "s3" is the region unless it is already "amazonaws".
            String region = (s3 + 1 < parts.length && !parts[s3 + 1].equals("amazonaws"))
                    ? parts[s3 + 1] : null;
            if (s3 > 0) {
                // bucket.s3.region.amazonaws.com — the bucket may itself contain dots.
                return new S3Location(String.join(".", java.util.Arrays.copyOfRange(parts, 0, s3)), region);
            }
            // s3.region.amazonaws.com/bucket/key
            String path = java.net.URI.create(url).getPath();
            if (path == null || path.length() < 2) return null;
            String first = path.substring(1).split("/", 2)[0];
            return first.isBlank() ? null : new S3Location(first, region);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * When a presigned URL stops being valid, or null if the URL is not presigned (or
     * carries no readable expiry).
     *
     * <p>Read from the link itself rather than tracked separately: this has to work for rows
     * written before the source location was recorded, where the URL is the only evidence
     * left. Note this is the signature's nominal expiry only — a URL signed by a shorter STS
     * session dies earlier than this and still 403s, so a null or future value here is not a
     * promise that the link works.
     */
    static Instant presignedExpiry(String url) {
        Matcher date = AMZ_DATE_PARAM.matcher(url);
        Matcher expires = AMZ_EXPIRES_PARAM.matcher(url);
        if (!date.find() || !expires.find()) return null;
        try {
            return Instant.from(AMZ_DATE.parse(date.group(1)))
                    .plusSeconds(Long.parseLong(expires.group(1)));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }
}
