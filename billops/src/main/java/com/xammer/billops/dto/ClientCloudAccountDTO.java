package com.xammer.billops.dto;

/**
 * Lightweight view of a {@code cloud_account} row for the BillOps user-manager account picker.
 *
 * <p>{@code accountId} is a derived, provider-specific identifier (first non-null of the AWS
 * account id, GCP project id, or Azure subscription id) because {@code CloudAccount} has no
 * single id column — this matches the {@code providerAccountId} already shown in
 * {@code ClientDetailsPage}.
 */
public class ClientCloudAccountDTO {

    private Long id;
    private String name;
    private String provider;
    private String accountId;

    /**
     * Owning client, carried so the cross-client list can say which customer an account belongs
     * to. Account names repeat across customers ("prod", "dev"), so without this the admin
     * picker offers rows that are impossible to tell apart.
     */
    private Long clientId;
    private String clientName;

    public ClientCloudAccountDTO() {
    }

    public ClientCloudAccountDTO(Long id, String name, String provider, String accountId) {
        this(id, name, provider, accountId, null, null);
    }

    public ClientCloudAccountDTO(Long id, String name, String provider, String accountId,
                                 Long clientId, String clientName) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.accountId = accountId;
        this.clientId = clientId;
        this.clientName = clientName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
}
