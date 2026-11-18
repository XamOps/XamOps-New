package com.xammer.cloud.service.autospotting.ledger.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsViewSource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The AWS-SDK implementation of {@link AwsPriceSource}. Spot history is read through the customer's
 * read-only session ({@code DescribeSpotPriceHistory}); list prices come from the public Price List with
 * the same filters the estimate path uses (design-aws.md §7.5), but this class never touches the
 * Redis-cached {@code SpotPricingService}, which caches $0 on failure (AWS-13, design-ledger.md §4.1).
 * Nothing is cached here: a value is either fetched now or the call throws.
 */
@Component
public class SdkAwsPriceSource implements AwsPriceSource {

    private static final int MAX_PAGES = 50;

    private final PricingClient pricing;
    private final AwsViewSource views;
    private final ObjectMapper mapper = new ObjectMapper();

    public SdkAwsPriceSource(PricingClient pricing, AwsViewSource views) {
        this.pricing = pricing;
        this.views = views;
    }

    @Override
    public List<SpotQuote> spotHistory(CloudAccount account, String region, List<String> instanceTypes,
                                       String productDescription, Instant since) {
        List<SpotQuote> out = new ArrayList<>();
        var view = views.view(account, region);
        String token = null;
        int pages = 0;
        do {
            final String next = token;
            DescribeSpotPriceHistoryResponse resp = view.ec2().describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .instanceTypesWithStrings(instanceTypes)
                            .productDescriptions(productDescription)
                            .startTime(since)
                            .nextToken(next).build());
            for (SpotPrice sp : resp.spotPriceHistory()) {
                if (sp.spotPrice() == null || sp.timestamp() == null || sp.availabilityZone() == null) {
                    continue;
                }
                out.add(new SpotQuote(sp.availabilityZone(), sp.instanceTypeAsString(), productDescription,
                        new BigDecimal(sp.spotPrice()), sp.timestamp()));
            }
            token = resp.nextToken();
            pages++;
            if (token != null && !token.isEmpty() && pages >= MAX_PAGES) {
                throw new IllegalStateException("Spot price history did not end within " + MAX_PAGES + " pages");
            }
        } while (token != null && !token.isEmpty());
        return out;
    }

    @Override
    public Optional<ListPrice> listPrice(String region, String instanceType, String operatingSystem,
                                         String preInstalledSw, String licenseModel) {
        List<String> products = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            final String next = token;
            GetProductsResponse resp = pricing.getProducts(GetProductsRequest.builder()
                    .serviceCode("AmazonEC2")
                    .filters(term("regionCode", region), term("instanceType", instanceType),
                            term("operatingSystem", operatingSystem), term("preInstalledSw", preInstalledSw),
                            term("licenseModel", licenseModel), term("tenancy", "Shared"),
                            term("capacitystatus", "Used"))
                    .nextToken(next).build());
            products.addAll(resp.priceList());
            token = resp.nextToken();
            pages++;
            if (token != null && pages >= MAX_PAGES) {
                throw new IllegalStateException("Price List did not end within " + MAX_PAGES + " pages");
            }
        } while (token != null);
        if (products.size() != 1) {
            return Optional.empty();
        }
        return parse(products.get(0));
    }

    private Optional<ListPrice> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String version = root.path("version").asText(null);
            String published = root.path("publicationDate").asText(null);
            for (JsonNode term : root.path("terms").path("OnDemand")) {
                String effective = term.path("effectiveDate").asText(null);
                for (JsonNode dim : term.path("priceDimensions")) {
                    String usd = dim.path("pricePerUnit").path("USD").asText(null);
                    if (usd == null || usd.isBlank() || effective == null || version == null || published == null) {
                        continue;
                    }
                    BigDecimal rate = new BigDecimal(usd);
                    if (rate.signum() > 0) {
                        return Optional.of(new ListPrice(rate, Instant.parse(effective),
                                version + "|" + published, json));
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Filter term(String field, String value) {
        return Filter.builder().type(FilterType.TERM_MATCH).field(field).value(value).build();
    }
}
