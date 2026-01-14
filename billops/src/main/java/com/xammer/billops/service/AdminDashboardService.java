package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AccountSummaryDto;
import com.xammer.billops.dto.AdminDashboardDto;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
public class AdminDashboardService {

    private static final Logger logger = LoggerFactory.getLogger(AdminDashboardService.class);

    @PostConstruct
    public void init() {
        logger.info("✅ AdminDashboardService initialized");
    }

    private final CloudAccountRepository cloudAccountRepository;
    private final InvoiceRepository invoiceRepository;
    private final TicketRepository ticketRepository;
    private final ClientRepository clientRepository;
    private final RedisCacheService redisCache;

    private static final String CACHE_KEY = "billops:admin:dashboard";
    private static final long CACHE_TTL_MINUTES = 1440; // 24 hours

    private final BillingService billingService;

    public AdminDashboardService(
            InvoiceRepository invoiceRepository,
            CloudAccountRepository cloudAccountRepository,
            TicketRepository ticketRepository,
            ClientRepository clientRepository,
            RedisCacheService redisCacheService,
            BillingService billingService) {
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.ticketRepository = ticketRepository;
        this.clientRepository = clientRepository;
        this.redisCache = redisCacheService;
        this.billingService = billingService;
    }

    public AdminDashboardDto getAdminDashboardSummary(boolean forceRefresh) {
        // Check cache first
        if (!forceRefresh) {
            Optional<AdminDashboardDto> cached = redisCache.get(CACHE_KEY, AdminDashboardDto.class);
            if (cached.isPresent()) {
                logger.info("Returning cached admin dashboard data");
                return cached.get();
            }
        }

        logger.info("Building admin dashboard summary");
        AdminDashboardDto dashboard = new AdminDashboardDto();

        // Get all cloud accounts
        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        dashboard.setTotalAccounts((long) allAccounts.size());

        // Count by provider
        long awsCount = allAccounts.stream().filter(a -> "AWS".equalsIgnoreCase(a.getProvider())).count();
        long gcpCount = allAccounts.stream().filter(a -> "GCP".equalsIgnoreCase(a.getProvider())).count();
        long azureCount = allAccounts.stream().filter(a -> "Azure".equalsIgnoreCase(a.getProvider())).count();

        dashboard.setAwsAccountsCount(awsCount);
        dashboard.setGcpAccountsCount(gcpCount);
        dashboard.setAzureAccountsCount(azureCount);

        // Count by status - will be set later after creating account summaries
        Map<String, Long> accountsByStatus = allAccounts.stream()
                .collect(Collectors.groupingBy(
                        acc -> acc.getStatus() != null ? acc.getStatus() : "UNKNOWN",
                        Collectors.counting()));
        dashboard.setAccountsByStatus(accountsByStatus);

        // Get invoice counts
        List<Invoice> allInvoices = invoiceRepository.findAll();
        long pendingInvoices = allInvoices.stream()
                .filter(inv -> inv.getStatus() == Invoice.InvoiceStatus.DRAFT)
                .count();
        long finalizedInvoices = allInvoices.stream()
                .filter(inv -> inv.getStatus() == Invoice.InvoiceStatus.FINALIZED)
                .count();

        dashboard.setPendingInvoices(pendingInvoices);
        dashboard.setFinalizedInvoices(finalizedInvoices);

        // Get open tickets count
        List<Ticket> allTickets = ticketRepository.findAll();
        long openTickets = allTickets.stream()
                .filter(t -> "OPEN".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus()))
                .count();
        dashboard.setOpenTickets(openTickets);

        // Get total clients
        Long totalClients = clientRepository.count();
        dashboard.setTotalClients(totalClients);

        // Calculate spending for all accounts
        YearMonth currentMonth = YearMonth.now();
        YearMonth lastMonth = currentMonth.minusMonths(1);

        List<AccountSummaryDto> accountSummaries = allAccounts.stream()
                .map(account -> {
                    BigDecimal currentSpending = calculateAccountSpending(account, currentMonth);
                    BigDecimal lastSpending = calculateAccountSpending(account, lastMonth);
                    LocalDate lastInvoiceDate = getLastInvoiceDate(account);
                    return convertToAccountSummary(account, currentSpending, lastSpending, lastInvoiceDate);
                })
                .collect(Collectors.toList());

        // Calculate total spending
        BigDecimal totalSpending = accountSummaries.stream()
                .map(acc -> acc.getCurrentMonthSpending() != null ? acc.getCurrentMonthSpending() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dashboard.setTotalSpending(totalSpending);

        // Top 10 spending accounts
        List<AccountSummaryDto> topAccounts = accountSummaries.stream()
                .sorted((a1, a2) -> {
                    BigDecimal s1 = a1.getCurrentMonthSpending() != null ? a1.getCurrentMonthSpending()
                            : BigDecimal.ZERO;
                    BigDecimal s2 = a2.getCurrentMonthSpending() != null ? a2.getCurrentMonthSpending()
                            : BigDecimal.ZERO;
                    return s2.compareTo(s1);
                })
                .limit(10)
                .collect(Collectors.toList());
        dashboard.setTopSpendingAccounts(topAccounts);

        // Spending by provider
        Map<String, BigDecimal> spendingByProvider = new HashMap<>();
        spendingByProvider.put("AWS", BigDecimal.ZERO);
        spendingByProvider.put("GCP", BigDecimal.ZERO);
        spendingByProvider.put("Azure", BigDecimal.ZERO);

        for (AccountSummaryDto account : accountSummaries) {
            BigDecimal spending = account.getCurrentMonthSpending() != null ? account.getCurrentMonthSpending()
                    : BigDecimal.ZERO;
            spendingByProvider.merge(account.getProvider(), spending, BigDecimal::add);
        }
        dashboard.setSpendingByProvider(spendingByProvider);

        // Recent invoices (last 10)
        List<Invoice> recentInvoiceEntities = invoiceRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "invoiceDate"))).getContent();

        List<InvoiceDto> recentInvoices = recentInvoiceEntities.stream()
                .map(this::convertToInvoiceDto)
                .collect(Collectors.toList());
        dashboard.setRecentInvoices(recentInvoices);

        // Recent tickets (last 10)
        List<Ticket> recentTicketEntities = ticketRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        List<TicketDto> recentTickets = recentTicketEntities.stream()
                .map(this::convertToTicketDto)
                .collect(Collectors.toList());
        dashboard.setRecentTickets(recentTickets);

        // Cache the result
        dashboard.setLastUpdated(System.currentTimeMillis());
        redisCache.put(CACHE_KEY, dashboard, CACHE_TTL_MINUTES);
        logger.info("Admin dashboard data cached for {} minutes", CACHE_TTL_MINUTES);

        return dashboard;
    }

    private BigDecimal calculateAccountSpending(CloudAccount account, YearMonth month) {
        try {
            // Use BillingService to get accurate spend (matches Billing Page logic)
            BillingDashboardDto billingData = billingService.getAccountBillingSummary(
                    account,
                    month.getYear(),
                    month.getMonthValue());

            // Sum up costs from service breakdown
            if (billingData.getServiceBreakdown() != null) {
                return billingData.getServiceBreakdown().stream()
                        .map(s -> BigDecimal.valueOf(s.getCost()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.warn("Error calculating spending for account {}: {}", account.getAccountName(), e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private LocalDate getLastInvoiceDate(CloudAccount account) {
        try {
            List<Invoice> invoices = invoiceRepository.findAll().stream()
                    .filter(inv -> inv.getCloudAccount() != null
                            && inv.getCloudAccount().getId().equals(account.getId()))
                    .sorted((i1, i2) -> i2.getInvoiceDate().compareTo(i1.getInvoiceDate()))
                    .limit(1)
                    .collect(Collectors.toList());

            if (!invoices.isEmpty()) {
                return invoices.get(0).getInvoiceDate();
            }
        } catch (Exception e) {
            logger.warn("Error getting last invoice date for account {}: {}", account.getAccountName(), e.getMessage());
        }
        return null;
    }

    private AccountSummaryDto convertToAccountSummary(CloudAccount account, BigDecimal currentMonthSpending,
            BigDecimal lastMonthSpending, LocalDate lastInvoiceDate) {
        AccountSummaryDto dto = new AccountSummaryDto();
        dto.setId(account.getId());
        dto.setAccountName(account.getAccountName());
        dto.setProvider(account.getProvider());
        dto.setStatus(account.getStatus());

        // Set account identifier based on provider
        if ("AWS".equalsIgnoreCase(account.getProvider())) {
            dto.setAccountIdentifier(account.getAwsAccountId());
        } else if ("GCP".equalsIgnoreCase(account.getProvider())) {
            dto.setAccountIdentifier(account.getGcpProjectId());
        } else if ("Azure".equalsIgnoreCase(account.getProvider())) {
            dto.setAccountIdentifier(account.getAzureSubscriptionId());
        }

        dto.setCurrentMonthSpending(currentMonthSpending);
        dto.setLastMonthSpending(lastMonthSpending);
        dto.setLastInvoiceDate(lastInvoiceDate);
        dto.setClientName(account.getClient() != null ? account.getClient().getName() : "N/A");
        dto.setClientId(account.getClient() != null ? account.getClient().getId() : null);

        return dto;
    }

    private InvoiceDto convertToInvoiceDto(Invoice invoice) {
        InvoiceDto dto = new InvoiceDto();
        dto.setId(invoice.getId());
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setInvoiceDate(invoice.getInvoiceDate());
        dto.setBillingPeriod(invoice.getBillingPeriod());
        dto.setStatus(invoice.getStatus().name());
        dto.setAmount(invoice.getAmount());
        dto.setPreDiscountTotal(invoice.getPreDiscountTotal());
        dto.setDiscountAmount(invoice.getDiscountAmount());
        dto.setTaxAmount(invoice.getTaxAmount());

        if (invoice.getCloudAccount() != null) {
            dto.setAccountName(invoice.getCloudAccount().getAccountName());
        }
        if (invoice.getClient() != null) {
            dto.setClientName(invoice.getClient().getName());
        }

        return dto;
    }

    private TicketDto convertToTicketDto(Ticket ticket) {
        TicketDto dto = new TicketDto();
        dto.setId(ticket.getId());
        dto.setSubject(ticket.getSubject());
        dto.setDescription(ticket.getDescription());
        dto.setStatus(ticket.getStatus());
        dto.setCategory(ticket.getCategory());
        dto.setSeverity(ticket.getSeverity());
        dto.setCreatedAt(ticket.getCreatedAt());

        if (ticket.getCreator() != null) {
            dto.setCreatorId(ticket.getCreator().getId());
        }

        return dto;
    }
}
