package com.xammer.billops.service.upload;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.service.AdminAccessScope;
import com.xammer.billops.dto.UploadedBillPreviewDto;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.service.AccountAccessHelper;
import com.xammer.billops.service.InvoiceManagementService;
import com.xammer.cloud.domain.Invoice;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Pattern;

/**
 * "Upload draft": turns an AWS console bill PDF into a draft invoice.
 *
 * <p>Two steps, both re-reading the file so the server is the only thing that ever
 * interprets it: {@link #preview} shows the admin what was found (account, period,
 * charges, anything doubtful), and {@link #createDraft} builds the draft with whatever
 * account and period the admin confirmed.
 *
 * <p>The bill's account does not have to be onboarded. An invoice has to belong to a
 * cloud account and a client, so when none exists for the bill's account ID one is added
 * -- invoice only, with no role or credentials, so nothing is ever read from it. Those
 * accounts, and the clients made for them, are excluded from every list but the Billing
 * pages by the repositories themselves.
 */
@Service
public class UploadedBillDraftService {

    private static final Logger logger = LoggerFactory.getLogger(UploadedBillDraftService.class);

    /** Multipart is unlimited app-wide; a console bill printout is a few hundred KB. */
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("\\d{12}");

    /** See {@link CloudAccount#STATUS_INVOICE_ONLY}. */
    static final String UPLOADED_ACCOUNT_STATUS = CloudAccount.STATUS_INVOICE_ONLY;

    private final AwsBillPdfParser parser;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    private final AccountAccessHelper accountAccessHelper;
    private final AdminAccessScope adminAccessScope;
    private final InvoiceManagementService invoiceManagementService;

    public UploadedBillDraftService(AwsBillPdfParser parser,
                                    CloudAccountRepository cloudAccountRepository,
                                    ClientRepository clientRepository,
                                    AccountAccessHelper accountAccessHelper,
                                    AdminAccessScope adminAccessScope,
                                    InvoiceManagementService invoiceManagementService) {
        this.parser = parser;
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.accountAccessHelper = accountAccessHelper;
        this.adminAccessScope = adminAccessScope;
        this.invoiceManagementService = invoiceManagementService;
    }

    /** What the admin chose for a bill whose account isn't in BillOps yet. All optional. */
    public record NewAccountDetails(String accountName, Long clientId, String clientName) {
    }

    public UploadedBillPreviewDto preview(MultipartFile file) {
        ParsedAwsBill bill = read(file);
        List<String> warnings = new ArrayList<>(bill.getWarnings());

        UploadedBillPreviewDto.MatchedAccount matched = null;
        boolean willCreate = false;
        if (bill.getAccountId() != null) {
            Optional<CloudAccount> account = findAccount(bill.getAccountId());
            if (account.isEmpty()) {
                willCreate = true;
            } else if (!canAccess(account.get())) {
                warnings.add(0, "You don't have access to account " + bill.getAccountId() + ". Choose an account you manage.");
            } else {
                CloudAccount a = account.get();
                matched = new UploadedBillPreviewDto.MatchedAccount(a.getId(), a.getAccountName(),
                        a.getAwsAccountId(), a.getProvider());
            }
        }

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ParsedAwsBill.Line line : bill.getLines()) {
            totals.merge(line.service(), line.cost(), BigDecimal::add);
            counts.merge(line.service(), 1, Integer::sum);
        }
        List<UploadedBillPreviewDto.ServiceSummary> services = totals.entrySet().stream()
                .map(e -> new UploadedBillPreviewDto.ServiceSummary(e.getKey(), e.getValue(), counts.get(e.getKey())))
                .sorted(Comparator.comparing(UploadedBillPreviewDto.ServiceSummary::total).reversed())
                .toList();

        YearMonth period = bill.getBillingPeriod();
        return new UploadedBillPreviewDto(
                file.getOriginalFilename(),
                bill.getPageCount(),
                bill.getAccountId(),
                matched,
                willCreate,
                willCreate ? defaultAccountName(bill.getAccountId()) : null,
                period != null ? period.toString() : null,
                period != null ? period.getYear() : null,
                period != null ? period.getMonthValue() : null,
                bill.getDatePrinted(),
                bill.isEstimated(),
                bill.getGrandTotal(),
                bill.getPreTaxTotal(),
                bill.getTaxTotal(),
                bill.getMarketplaceTotal(),
                bill.lineTotal(),
                bill.getLines().size(),
                bill.roundingLineCount(),
                bill.roundingTotal(),
                services,
                warnings);
    }

    /**
     * Builds the draft. {@code accountId}, {@code year} and {@code month} are the admin's
     * confirmed choices; any left blank fall back to what the PDF says. When the account
     * is not in BillOps it is added first, in the same transaction, so a failed draft
     * leaves no stray account behind.
     */
    @Transactional
    public Invoice createDraft(MultipartFile file, String accountId, Integer year, Integer month, boolean includeIrn,
                               NewAccountDetails newAccount) {
        ParsedAwsBill bill = read(file);

        String id = accountId != null && !accountId.isBlank() ? accountId.trim() : bill.getAccountId();
        if (id == null) {
            throw new UploadedBillException(HttpStatus.BAD_REQUEST,
                    "No AWS account ID was found in the PDF. Choose the account to bill.");
        }

        CloudAccount account = findAccount(id).orElse(null);
        if (account == null) {
            account = createBillingOnlyAccount(id.replace("-", ""), newAccount);
        } else if (!canAccess(account)) {
            throw new UploadedBillException(HttpStatus.FORBIDDEN, "You don't have access to account " + id + ".");
        }

        YearMonth period;
        try {
            period = year != null && month != null ? YearMonth.of(year, month) : bill.getBillingPeriod();
        } catch (DateTimeException e) {
            throw new UploadedBillException(HttpStatus.BAD_REQUEST, "That billing period isn't a valid month.");
        }
        if (period == null) {
            throw new UploadedBillException(HttpStatus.BAD_REQUEST,
                    "No billing period was found in the PDF. Choose the month this bill is for.");
        }

        if (bill.getLines().isEmpty()) {
            throw new UploadedBillException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No charges could be read from this PDF. Upload the bill printed from the AWS Billing console's Bills page.");
        }
        if (bill.getAccountId() != null && !bill.getAccountId().equals(account.getAwsAccountId())) {
            logger.info("[BillUpload] Admin billed account {} from a PDF printed for account {}",
                    account.getAwsAccountId(), bill.getAccountId());
        }

        return invoiceManagementService.createDraftFromUploadedBill(account, period, includeIrn, bill,
                file.getOriginalFilename(), accountAccessHelper.currentAppUserId());
    }

    /**
     * Adds the bill's AWS account so the draft has something to belong to: provider AWS,
     * no role or credentials, under the client the admin chose -- an existing one, one
     * found by name, or a new one.
     *
     * <p>The account is invoice-only: it is kept out of every account and client list except the
     * Billing pages (see {@code CloudAccountRepository}). The admin who uploaded the bill is
     * recorded on the invoice, and that, not an entry on their access list, is what lets an
     * admin with limited access see their own draft.
     */
    private CloudAccount createBillingOnlyAccount(String awsAccountId, NewAccountDetails details) {
        if (!AWS_ACCOUNT_ID.matcher(awsAccountId).matches()) {
            throw new UploadedBillException(HttpStatus.BAD_REQUEST,
                    "Account " + awsAccountId + " isn't set up in BillOps, and it isn't a 12-digit AWS account ID.");
        }
        NewAccountDetails d = details != null ? details : new NewAccountDetails(null, null, null);

        Client client = resolveClient(awsAccountId, d);
        String name = d.accountName() != null && !d.accountName().isBlank()
                ? d.accountName().trim() : defaultAccountName(awsAccountId);

        CloudAccount account = new CloudAccount();
        account.setAccountName(clip(name));
        account.setAwsAccountId(awsAccountId);
        account.setProvider("AWS");
        account.setStatus(UPLOADED_ACCOUNT_STATUS);
        account.setAccessType("billing-only");
        account.setClient(client);
        CloudAccount saved = cloudAccountRepository.save(account);
        logger.info("[BillUpload] Added invoice-only AWS account {} ('{}') under client '{}' (id={})",
                awsAccountId, saved.getAccountName(), client.getName(), client.getId());
        return saved;
    }

    /**
     * The client the new account goes under: the one chosen, one found by name, or a new one.
     *
     * <p>An admin whose access is limited may use an existing client only if it is one of
     * theirs, or one that exists only for uploaded bills (anyone may reuse those; they hold
     * nothing else). Otherwise typing another customer's name would attach an account to a
     * client they cannot see.
     */
    private Client resolveClient(String awsAccountId, NewAccountDetails d) {
        Client client;
        if (d.clientId() != null) {
            client = clientRepository.findById(d.clientId()).orElseThrow(() ->
                    new UploadedBillException(HttpStatus.BAD_REQUEST, "That client no longer exists. Choose another."));
        } else {
            String name = d.clientName() != null && !d.clientName().isBlank()
                    ? d.clientName().trim() : defaultAccountName(awsAccountId);
            Client existing = clientRepository.findByName(name); // Names are unique: the same name is the same client.
            if (existing == null) {
                return clientRepository.save(new Client(clip(name)));
            }
            client = existing;
        }

        if (accountAccessHelper.getAllowedDbIds() != null) {
            Set<Long> myClients = adminAccessScope.clientIdsForCurrentUser();
            boolean mine = myClients != null && myClients.contains(client.getId());
            boolean invoiceOnlyClient = cloudAccountRepository.findInvoiceOnlyClientIds().contains(client.getId());
            if (!mine && !invoiceOnlyClient) {
                throw new UploadedBillException(HttpStatus.FORBIDDEN,
                        "You don't have access to client \"" + client.getName() + "\". Choose one of your clients, "
                                + "or enter a new client name.");
            }
        }
        return client;
    }

    private static String defaultAccountName(String awsAccountId) {
        return "AWS account " + awsAccountId;
    }

    private static String clip(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private ParsedAwsBill read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UploadedBillException(HttpStatus.BAD_REQUEST, "Choose a PDF to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new UploadedBillException(HttpStatus.CONTENT_TOO_LARGE, "That file is over 25 MB. Upload the bill PDF itself.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UploadedBillException(HttpStatus.BAD_REQUEST, "The upload couldn't be read. Try again.");
        }
        // Checked by content, not name or Content-Type, both of which the client chooses.
        if (bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII))) {
            throw new UploadedBillException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "That file isn't a PDF.");
        }
        try {
            return parser.parse(bytes);
        } catch (InvalidPasswordException e) {
            throw new UploadedBillException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The PDF is password protected. Upload an unprotected copy.");
        } catch (IOException | RuntimeException e) {
            logger.warn("[BillUpload] Could not parse '{}': {}", file.getOriginalFilename(), e.getMessage(), e);
            throw new UploadedBillException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The PDF couldn't be read. Upload the bill printed from the AWS Billing console's Bills page.");
        }
    }

    /** By AWS account id first (case- and space-tolerant), then by any provider id. */
    private Optional<CloudAccount> findAccount(String id) {
        Optional<CloudAccount> aws = cloudAccountRepository.findByAwsAccountId(id).stream().findFirst();
        if (aws.isPresent()) {
            return aws;
        }
        try {
            return cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(id, id, id);
        } catch (RuntimeException e) { // More than one match: refuse to guess, and don't add another.
            logger.warn("[BillUpload] Ambiguous account id {}: {}", id, e.getMessage());
            throw new UploadedBillException(HttpStatus.CONFLICT,
                    "More than one BillOps account uses the ID " + id + ". Choose the account to bill.");
        }
    }

    /**
     * Sub-users may only bill the accounts they are assigned; primary admins see all. An
     * invoice-only account is open to any admin who can upload: it holds no data, and the
     * drafts on it are each visible only to whoever uploaded them.
     */
    private boolean canAccess(CloudAccount account) {
        if (account.isInvoiceOnly()) {
            return true;
        }
        Set<Long> allowed = accountAccessHelper.getAllowedDbIds();
        return allowed == null || allowed.contains(account.getId());
    }
}
