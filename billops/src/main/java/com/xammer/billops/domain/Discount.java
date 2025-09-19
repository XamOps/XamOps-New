package com.xammer.billops.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Discount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceName;

    @Column(precision = 5, scale = 2)
    private BigDecimal percentage;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    // --- START OF FINAL FIX ---
    // This annotation now tells Hibernate to use the column name "client_id",
    // which exactly matches your database's requirement.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;
    // --- END OF FINAL FIX ---


    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Invoice getInvoice() { return invoice; }
    public void setInvoice(Invoice invoice) { this.invoice = invoice; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
}