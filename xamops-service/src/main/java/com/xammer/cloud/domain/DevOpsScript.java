package com.xammer.cloud.domain;

// Restore javax.persistence annotations for Spring Boot 2.x
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Column;

@Entity // <-- Add back
public class DevOpsScript {

    @Id // <-- Add back
    @GeneratedValue(strategy = GenerationType.IDENTITY) // <-- Add back
    private Long id;

    private String name;
    private String description;
    private String category;
    private int downloads;
    private String lastUpdated;
    private String version;

    @Lob // <-- Add back
    @Column(columnDefinition = "TEXT") // <-- Add back
    private String code;

    @Lob // <-- Add back
    @Column(columnDefinition = "TEXT") // <-- Add back
    private String usageInstructions; // Mapped to 'usage' in frontend

    // Constructors
    public DevOpsScript() {}

    public DevOpsScript(String name, String description, String category, String code, String usageInstructions, String version, int downloads, String lastUpdated) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.code = code;
        this.usageInstructions = usageInstructions;
        this.version = version;
        this.downloads = downloads;
        this.lastUpdated = lastUpdated;
    }

    // --- Getters and Setters ---
    // (Ensure all getters and setters are present)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getDownloads() { return downloads; }
    public void setDownloads(int downloads) { this.downloads = downloads; }
    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUsageInstructions() { return usageInstructions; }
    public void setUsageInstructions(String usageInstructions) { this.usageInstructions = usageInstructions; }
}