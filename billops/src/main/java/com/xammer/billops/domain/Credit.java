package com.xammer.billops.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
public class Credit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String creditNumber;
    private LocalDate creditDate;
    private double amount;
    private String reason;

    @ManyToOne
    @JoinColumn(name = "client_id", nullable = false) // UPDATED: Changed from customer_id
    private Client client; // UPDATED: Changed from Customer

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCreditNumber() {
        return creditNumber;
    }

    public void setCreditNumber(String creditNumber) {
        this.creditNumber = creditNumber;
    }

    public LocalDate getCreditDate() {
        return creditDate;
    }

    public void setCreditDate(LocalDate creditDate) {
        this.creditDate = creditDate;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }
}