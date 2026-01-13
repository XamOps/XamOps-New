package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AccountSummaryDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.UserDashboardDto;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
public class UserDashboardService {

    private static final Logger logger = LoggerFactory.getLogger(UserDashboardService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final InvoiceRepository invoiceRepository;
    private final TicketRepository ticketRepository;
    private final RedisCacheService redisCache;

    private static final String CACHE_KEY_PREFIX = "billops:user:dashboard:";
    private static final long CACHE_TTL_MINUTES = 1440; // 24 hours

    private final BillingService billingService;

    public UserDashboardService(CloudAccountRepository cloudAccountRepository,
            InvoiceRepository invoiceRepository,
            TicketRepository ticketRepository,
            RedisCacheService redisCache,
            BillingService billingService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.invoiceRepository = invoiceRepository;
        this.ticketRepository = ticketRepository;
        this.redisCache = redisCache;
        this.billingService = billingService;
    }

    public UserDashboardDto getUserDashboardSummary(AppUser user, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_PREFIX + user.getId();

        // Check cache first
        if (!forceRefresh) {
            Optional<UserDashboardDto> cached = redisCache.get(cacheKey, UserDashboardDto.class);
            if (cached.isPresent()) {
                logger.info("Returning cached user dashboard data for user: {}", user.getUsername());
                return cached.get();
            }
        }

        logger.info("Building user dashboard summary for user: {}", user.getUsername());
        UserDashboardDto dashboard = new UserDashboardDto();

        // Set user info
        dashboard.setUserId(user.getId());
        dashboard.setUsername(user.getUsername());
        dashboard.setClientName(user.getClient() != null ? user.getClient().getName() : "N/A");

        // Get user's cloud accounts
        List<CloudAccount> userAccounts = cloudAccountRepository.findByClientId(user.getClient().getId());
        dashboard.setTotalAccounts((long) userAccounts.size());

        // Count by provider
        long awsCount = userAccounts.stream().filter(a -> "AWS".equalsIgnoreCase(a.getProvider())).count();
        long gcpCount = userAccounts.stream().filter(a -> "GCP".equalsIgnoreCase(a.getProvider())).count();
        long azureCount = userAccounts.stream().filter(a -> "Azure".equalsIgnoreCase(a.getProvider())).count();

        dashboard.setAwsAccountsCount(awsCount);
        dashboard.setGcpAccountsCount(gcpCount);
        dashboard.setAzureAccountsCount(azureCount);

        // Calculate spending for all user's accounts
        YearMonth currentMonth = YearMonth.now();
        YearMonth lastMonth = currentMonth.minusMonths(1);

        List<AccountSummaryDto> accountSummaries = userAccounts.stream()
                .map(account -> {
                    BigDecimal currentSpending = calculateAccountSpending(account, currentMonth);
                    BigDecimal lastSpending = calculateAccountSpending(account, lastMonth);
                    LocalDate lastInvoiceDate = getLastInvoiceDate(account);
                    return convertToAccountSummary(account, currentSpending, lastSpending, lastInvoiceDate);
                })
                .collect(Collectors.toList());

        dashboard.setAccounts(accountSummaries);

        // Calculate current month spending
        BigDecimal currentMonthSpending = accountSummaries.stream()
                .map(acc -> acc.getCurrentMonthSpending() != null ? acc.getCurrentMonthSpending() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dashboard.setCurrentMonthSpending(currentMonthSpending);

        // Calculate last month spending
        BigDecimal lastMonthSpending = accountSummaries.stream()
                .map(acc -> acc.getLastMonthSpending() != null ? acc.getLastMonthSpending() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dashboard.setLastMonthSpending(lastMonthSpending);

        // Calculate total spending (last 12 months)
        BigDecimal totalSpending = calculateTotalSpending(userAccounts);
        dashboard.setTotalSpending(totalSpending);

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

        // Get spending trend (last 6 months)
        List<UserDashboardDto.CostHistory> spendingTrend = calculateSpendingTrend(userAccounts, 6);
        dashboard.setSpendingTrend(spendingTrend);

        // Get invoice counts for user's client
        List<Invoice> userInvoices = invoiceRepository.findAll().stream()
                .filter(inv -> inv.getClient() != null && inv.getClient().getId().equals(user.getClient().getId()))
                .collect(Collectors.toList());

        long pendingInvoices = userInvoices.stream()
                .filter(inv -> inv.getStatus() == Invoice.InvoiceStatus.DRAFT)
                .count();
        dashboard.setPendingInvoices(pendingInvoices);

        // Recent invoices (last 5)
        List<Invoice> recentInvoiceEntities = userInvoices.stream()
                .sorted((i1, i2) -> i2.getInvoiceDate().compareTo(i1.getInvoiceDate()))
                .limit(5)
                .collect(Collectors.toList());

        List<InvoiceDto> recentInvoices = recentInvoiceEntities.stream()
                .map(this::convertToInvoiceDto)
                .collect(Collectors.toList());
        dashboard.setRecentInvoices(recentInvoices);

        // Get tickets for user's client
        List<Ticket> userTickets = ticketRepository.findAll().stream()
                .filter(t -> t.getClient() != null && t.getClient().getId().equals(user.getClient().getId()))
                .collect(Collectors.toList());

        long openTickets = userTickets.stream()
                .filter(t -> "OPEN".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus()))
                .count();
        dashboard.setOpenTickets(openTickets);

        // Recent tickets (last 5)
        List<Ticket> recentTicketEntities = userTickets.stream()
                .sorted((t1, t2) -> t2.getCreatedAt().compareTo(t1.getCreatedAt()))
                .limit(5)
                .collect(Collectors.toList());

        List<TicketDto> recentTickets = recentTicketEntities.stream()
                .map(this::convertToTicketDto)
                .collect(Collectors.toList());
        dashboard.setRecentTickets(recentTickets);

        // Cache the result
        dashboard.setLastUpdated(System.currentTimeMillis());
        redisCache.put(cacheKey, dashboard, CACHE_TTL_MINUTES);
        logger.info("User dashboard data cached for {} minutes", CACHE_TTL_MINUTES);

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

    private BigDecimal calculateTotalSpending(List<CloudAccount> accounts) {
        BigDecimal total = BigDecimal.ZERO;
        YearMonth currentMonth = YearMonth.now();

        for (int i = 0; i < 12; i++) {
            YearMonth month = currentMonth.minusMonths(i);
            for (CloudAccount account : accounts) {
                total = total.add(calculateAccountSpending(account, month));
            }
        }

        return total;
    }

    private List<UserDashboardDto.CostHistory> calculateSpendingTrend(List<CloudAccount> accounts, int months) {
        List<UserDashboardDto.CostHistory> trend = new ArrayList<>();
        YearMonth currentMonth = YearMonth.now();

        for (int i = months - 1; i >= 0; i--) {
            YearMonth month = currentMonth.minusMonths(i);
            double totalCost = 0.0;

            for (CloudAccount account : accounts) {
                BigDecimal cost = calculateAccountSpending(account, month);
                totalCost += cost.doubleValue();
            }

            trend.add(new UserDashboardDto.CostHistory(month.toString(), totalCost));
        }

        return trend;
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
