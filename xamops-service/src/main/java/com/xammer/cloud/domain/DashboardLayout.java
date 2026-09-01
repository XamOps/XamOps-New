package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;

@Entity
@Data
@NoArgsConstructor
public class DashboardLayout {

    @Id
    private String username;

    @Lob
    @Column(nullable = false)
    private String layoutConfig; // Store layout as a JSON string

    public DashboardLayout(String username, String layoutConfig) {
        this.username = username;
        this.layoutConfig = layoutConfig;
    }
}
