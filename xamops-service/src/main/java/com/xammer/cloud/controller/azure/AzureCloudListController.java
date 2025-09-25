package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.azure.AzureCloudListService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/azure/cloudlist")
public class AzureCloudListController {

    private final AzureCloudListService azureCloudListService;

    public AzureCloudListController(AzureCloudListService azureCloudListService) {
        this.azureCloudListService = azureCloudListService;
    }

    @GetMapping("/resources")
    public List<DashboardData.ServiceGroupDto> getResources(@RequestParam String accountIds) {
        // This handles only the first account ID for Azure's single-selection model.
        String accountId = accountIds.split(",")[0];
        return azureCloudListService.getAzureResources(accountId);
    }
}