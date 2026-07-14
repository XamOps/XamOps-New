# --- Storage Policy for Cost Export (Blobs delete after 14 days) ---
$StoragePolicy = '
{
  "rules": [
    {
      "enabled": true,
      "name": "delete-blobs-after-two-weeks",
      "type": "Lifecycle",
      "definition": {
        "actions": {
          "baseBlob": {
            "delete": {
              "daysAfterCreationGreaterThan": 14
            }
          }
        },
        "filters": {
          "blobTypes": [
            "blockBlob"
          ]
        }
      }
    }
  ]
}'

# --- FUNCTION 1: Creates the Service Principal and assigns "Reader" role ---
function Setup-XamOpsConnection {
    param (
        [Parameter(Mandatory = $true)]
        [string]$Scope,
       
        [Parameter(Mandatory = $false)]
        [string]$SubscriptionId,
       
        [Parameter(Mandatory = $false)]
        [string]$TenantId,

        [Parameter(Mandatory = $false)]
        [string]$ApplicationName = "XamOps"
    )

    Write-Host "--- Part 1: Setting up XamOps Service Principal ---"
   
    if (-not $TenantId) {
        $TenantId = (Get-AzContext).Tenant.Id
    }

    $principal = Get-AzADServicePrincipal -DisplayName $ApplicationName | Select-Object -First 1
    if ($principal) {
        Write-Host "Reusing existing service principal $ApplicationName ($($principal.Id)); a new secret will be added to it."
    } else {
        Write-Host "Creating new service principal $ApplicationName"
        $principal = New-AzADServicePrincipal -DisplayName $ApplicationName
    }
    if (-not $principal) {
        Write-Error "Failed to create service principal. Please check your permissions to create AD service principals and try again."
        return $null
    }

    $connectionConfig = @{
        tenant_id = $TenantId
        principal_id = $principal.Id
        client_id = $principal.AppId
        subscription_id = $SubscriptionId
        application_name = $ApplicationName
        access = "Read"
        scope = $Scope
    }

    Write-Host "Creating new 24-month secret for service principal $ApplicationName ($($principal.Id))."
    $secret = New-AzADSpCredential -ObjectId $principal.Id -StartDate (Get-Date) -EndDate (Get-Date).AddMonths(24)
    if (-not $secret) {
        Write-Error "Failed to create a new secret for principal $($principal.Id)."
        return $null
    }
    $connectionConfig["client_secret"] = $secret.SecretText
 
    $roleName = "Reader"
    Write-Host "Assigning the built-in '$roleName' role to Service Principal for monitoring."

    $roleAssignment = Get-AzRoleAssignment -ObjectId $principal.Id -Scope $Scope `
        | Where-Object {$_.RoleDefinitionName -eq $roleName} | Select-Object -First 1
       
    if (-not $roleAssignment) {
        Write-Host "Assigning $roleName role to $ApplicationName application at scope $Scope."
        $roleAssignment = New-AzRoleAssignment -ObjectId $principal.Id -RoleDefinitionName $roleName -Scope $Scope
        if (-not $roleAssignment) {
            Write-Error "Failed to assign $roleName role. Please check your permissions to assign roles at scope $Scope and try again."
            return $null
        }
    } else {
        Write-Host "'$roleName' role is already assigned."
    }

    Write-Host "--- Part 1 Complete: Service Principal created. ---"
    return $connectionConfig
}

# --- FUNCTION 2: Creates the Storage Account and Cost Export rules (as the User) ---
function Setup-CostExports {
    param (
        [Parameter(Mandatory = $true)]
        [string]$Scope,

        [Parameter(Mandatory = $true)]
        [string]$PrincipalId, # The Service Principal ID from Part 1
       
        [Parameter(Mandatory = $false)]
        [string]$ResourceGroupName = "xamops-billing-rg", # Use the same RG name as your Java code

        [Parameter(Mandatory = $false)]
        [string]$Location = "westeurope",

        [Parameter(Mandatory = $false)]
        [string]$HistoryMonths = 12
    )
   
    Write-Host "--- Part 2: Setting up Cost Export resources (as current user) ---"
   
    # Ensure az cli is installed and providers are registered
    try {
        az --version | Out-Null
    } catch {
        Write-Error "Azure CLI (az) is not installed or found in path. Please install it to continue."
        return $null
    }
    # az CLI keeps its own active subscription, independent of the Az PowerShell
    # context set by Set-AzContext. Align it to the scope being onboarded so the
    # billing resources land in the same subscription as the cost exports.
    if ($Scope -match '^/subscriptions/([0-9a-fA-F\-]+)') {
        $scopeSubscriptionId = $Matches[1]
        $output = az account set --subscription $scopeSubscriptionId 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Azure CLI could not switch to subscription ${scopeSubscriptionId}: $output. Run 'az login' and try again."
            return $null
        }
        Write-Host "Azure CLI subscription set to $scopeSubscriptionId."
    }

    az config set extension.use_dynamic_install=yes_without_prompt | Out-Null
    Write-Host "Registering Microsoft.CostManagementExports provider..."
    az provider register --namespace "Microsoft.CostManagementExports" --wait | Out-Null
    Write-Host "Provider registered."

    $TokenSet = [Char[]]"abcdefghijklmnopqrstuvwxyz0123456789"
    $timestamp = Get-Date -Format "yyMMddHHmm"
    $token = $((Get-Random -Count 7 -InputObject $TokenSet) -join '')

    # Create Resource Group
    $rg = az group show --name $ResourceGroupName --query "id" -o tsv 2>$null
    if (-not $rg) {
        Write-Host "Creating new resource group $ResourceGroupName in $Location."
        $output = az group create -l $Location -n $ResourceGroupName --query "id" -o tsv 2>&1
        if ($LASTEXITCODE -ne 0 -and "$output" -match "DisallowedLocation" -and "$output" -match "permitted regions is '([^']+)'") {
            # Some subscriptions (e.g. Jio India offers) restrict deployable regions;
            # the DisallowedLocation error message lists the permitted ones.
            $PermittedLocations = @($Matches[1] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notin @('global', 'devfabric') })
            if ($PermittedLocations.Count -gt 0) {
                Write-Host "Location '$Location' is not permitted for this subscription. Retrying in permitted region '$($PermittedLocations[0])'."
                $Location = $PermittedLocations[0]
                $output = az group create -l $Location -n $ResourceGroupName --query "id" -o tsv 2>&1
            }
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Failed to create resource group '$ResourceGroupName': $output"
            return $null
        }
        $rg = "$output"
    } else {
         Write-Host "Using existing resource group $ResourceGroupName."
    }

    #
    # --- THIS IS THE FIX for AccountNameInvalid ---
    #
    $StorageAccountName = "xamops" + $timestamp + $token # 6 + 10 + 7 = 23 chars (Valid)
    #
    # --- END FIX ---
    #
    Write-Host "Creating new storage account $StorageAccountName in resource group $ResourceGroupName."
    # Storage accounts are not offered in every region a subscription may deploy to
    # (e.g. jioindiacentral has no Microsoft.Storage), so the account may need a
    # different region than the resource group. Start with the RG's region and fall
    # back using the region lists Azure returns in its own error messages.
    $storageLocation = az group show --name $ResourceGroupName --query "location" -o tsv
    $StorageAccountId = $null
    $storageAvailable = @()
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        $output = az storage account create `
            --resource-group $ResourceGroupName `
            --name $StorageAccountName `
            --location $storageLocation `
            --kind StorageV2 `
            --sku Standard_LRS `
            --tags xamops-resource-usage=billing-export `
            --allow-blob-public-access false `
            --query id --output tsv 2>&1
        if ($LASTEXITCODE -eq 0) {
            $StorageAccountId = [string](@($output) -match '^/subscriptions/' | Select-Object -First 1)
            if ($StorageAccountId) { break }
        }
        if ("$output" -match "LocationNotAvailableForResourceType" -and "$output" -match "available regions for the resource type is '([^']+)'") {
            $storageAvailable = @($Matches[1] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            $next = @($storageAvailable | Where-Object { $PermittedLocations -and $_ -in $PermittedLocations }) | Select-Object -First 1
            if (-not $next) { $next = $storageAvailable | Select-Object -First 1 }
            if (-not $next -or $next -eq $storageLocation) { break }
            Write-Host "Region '$storageLocation' does not offer storage accounts. Retrying in '$next'."
            $storageLocation = $next
        }
        elseif ("$output" -match "DisallowedLocation" -and "$output" -match "permitted regions is '([^']+)'") {
            $PermittedLocations = @($Matches[1] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notin @('global', 'devfabric') })
            $next = @($storageAvailable | Where-Object { $_ -in $PermittedLocations }) | Select-Object -First 1
            if (-not $next) { $next = $PermittedLocations | Select-Object -First 1 }
            if (-not $next -or $next -eq $storageLocation) { break }
            Write-Host "Location '$storageLocation' is not permitted for this subscription. Retrying in '$next'."
            $storageLocation = $next
        }
        else {
            break
        }
    }

    # Check if Storage Account was created
    if (-not $StorageAccountId) {
        Write-Error "Storage Account creation failed for '$StorageAccountName': $output"
        return $null
    }
    Write-Host "Created new storage account $StorageAccountName with id $StorageAccountId."

    # Create 'costexports' container
    Write-Host "Creating 'costexports' container..."
    az storage container create --name "costexports" --account-name $StorageAccountName --auth-mode login | Out-Null
   
    # Apply Lifecycle Policy
    az storage account management-policy create `
        --resource-group $ResourceGroupName `
        --account-name $StorageAccountName `
        --policy $StoragePolicy `
        --query id --output tsv | Out-Null

    Write-Host "Configuring cost export for $Scope to storage account $StorageAccountId."
    $exportName = 'xamops-daily-actualcost'
    $exportId = Setup-CostManagementExport `
        -ExportName $exportName `
        -Scope $Scope `
        -StorageDirectory $('daily-actualcost-' + $token) `
        -StorageAccountId $StorageAccountId `
        -TimeFrame "MonthToDate" `
        -PeriodStart $(Get-Date -Format "yyyy-MM-ddT00:00:00Z") `
        -PeriodEnd "2050-12-31T00:00:00Z" `
        -Recurrent $true
    if (-not $exportId) {
        return $null
    }

    # Run the new export immediately
    Invoke-ExportRun -Scope $Scope -ExportName $exportName | Out-Null

    # --- THIS IS THE CRITICAL PERMISSION ---
    Write-Host "Assigning 'Storage Blob Data Reader' role to the XamOps Service Principal ($PrincipalId) on $StorageAccountId."
    $assignmentId = az role assignment create `
        --role "Storage Blob Data Reader" `
        --assignee-object-id $PrincipalId `
        --assignee-principal-type ServicePrincipal `
        --scope $StorageAccountId `
        --query id --output tsv
    if (-not $assignmentId) {
        Write-Error "Failed to assign 'Storage Blob Data Reader' role to Service Principal."
        return $null
    }
    Write-Host "Role assigned successfully."

    # --- Historical Cost Exports (Optional but recommended) ---
    for ($i = 0; $i -lt $HistoryMonths; $i++) {
        $month = $(Get-Date).AddMonths(-$i-1)
        $monthStart = $month.ToString('yyyy-MM-01T00:00:00Z')
        $monthEnd = $month.AddDays(1-$month.Day).AddMonths(1).AddDays(-1).ToString('yyyy-MM-ddT23:59:59Z')
        $exportName = $('xamops-actualcost-' + $month.ToString('yyyy-MM'))

        Write-Host "Configuring one-time cost export $exportName for $Scope from $monthStart to $monthEnd."
        $exportId = Setup-CostManagementExport `
            -ExportName $exportName `
            -Scope $Scope `
            -StorageDirectory $($month.ToString('yyyy-MM') + '-actualcost-' + $token) `
            -StorageAccountId $StorageAccountId `
            -TimeFrame "Custom" `
            -PeriodStart $monthStart `
            -PeriodEnd $monthEnd `
            -Recurrent $false
        if (-not $exportId) {
            return $null
        }

        Invoke-ExportRun -Scope $Scope -ExportName $exportName | Out-Null
    }
   
    Write-Host "--- Part 2 Complete: Cost export resources created. ---"
    return $StorageAccountId # Return storage ID for confirmation
}

# --- FUNCTION 3: Helper for creating export rules ---
function Setup-CostManagementExport {
    param (
        [string]$ExportName,
        [string]$Scope,
        [string]$StorageAccountId,
        [string]$StorageDirectory,
        [string]$TimeFrame,
        [string]$PeriodStart,
        [string]$PeriodEnd,
        [Parameter(Mandatory = $false)]
        [bool]$Recurrent = $false,
        [int]$MaxRetries = 10,
        [int]$WaitPeriodsSeconds = 10,
        [Parameter(Mandatory = $false)]
        [string]$ExportType = "ActualCost"
    )

    $retryCount = 0
   
    do {
        if ($Recurrent) {
            $output = az costmanagement export create `
                --name $ExportName `
                --storage-container costexports `
                --storage-directory $StorageDirectory `
                --type $ExportType `
                --scope $Scope `
                --storage-account-id $StorageAccountId `
                --timeframe MonthToDate `
                --recurrence Daily `
                --schedule-status Active `
                --recurrence-period from=$PeriodStart to=$PeriodEnd `
                --query id --output tsv 2>&1
        } else {
            $output = az costmanagement export create `
                --name $ExportName `
                --storage-account-id $StorageAccountId `
                --storage-container costexports `
                --storage-directory $StorageDirectory `
                --type $ExportType `
                --scope $Scope `
                --timeframe Custom `
                --time-period from=$PeriodStart to=$PeriodEnd `
                --query id --output tsv 2>&1
        }
           
        if ($LASTEXITCODE -eq 0 -and $output) {
            Write-Host "Export rule '$ExportName' created successfully with ID: $output"
            return $output
        }
        else {
            if ($output -match "not registered") {
                Write-Host "Resource Provider Microsoft.CostManagementExports not registered. Attempting to register..."
                az provider register --namespace "Microsoft.CostManagementExports" --wait
                Write-Host "Resource Provider Microsoft.CostManagementExports registered. Waiting $WaitPeriodsSeconds seconds for changes to propagate..."
                Start-Sleep -Seconds $WaitPeriodsSeconds
                $retryCount++
            }
            elseif ($output -match "'${ExportType}' is not a valid value for '--type'") {
                Write-Host "Invalid export type ${ExportType}. Switching to Usage."
                $ExportType = "Usage"
                $retryCount++
            }
            elseif ($output -match "Invalid definition type '${ExportType}'") {
                Write-Host "Invalid export type ${ExportType}. Switching to Usage."
                $ExportType = "Usage"
                $retryCount++
            }
            else {
                Write-Error "An error occurred creating export rule '$ExportName': $output"
                break
            }
        }
    } while ($retryCount -lt $maxRetries)

    if ($retryCount -ge $maxRetries) {
        Write-Error "Failed to create export '$ExportName' after $maxRetries retries."
    }
    return $null
}

# --- FUNCTION 4: Triggers an export run, retrying when ARM throttles (HTTP 429) ---
function Invoke-ExportRun {
    param (
        [string]$Scope,
        [string]$ExportName,
        [int]$MaxRetries = 3,
        [int]$RetryDelaySeconds = 60
    )

    $exportUri = "https://management.azure.com/$Scope/providers/Microsoft.CostManagement/exports/$ExportName/run?api-version=2023-08-01"
    for ($try = 1; $try -le $MaxRetries; $try++) {
        $at = az account get-access-token --query accessToken --output tsv
        $output = az rest --method POST --uri $exportUri --headers $('Authorization=Bearer ' + $at) 2>&1
        if ($LASTEXITCODE -eq 0) {
            return $true
        }
        if ("$output" -match "429|Too [Mm]any [Rr]equests" -and $try -lt $MaxRetries) {
            Write-Host "Throttled while triggering run of export '$ExportName'. Retrying in $RetryDelaySeconds seconds..."
            Start-Sleep -Seconds $RetryDelaySeconds
        } else {
            Write-Warning "Could not trigger run of export '$ExportName': $output"
            return $false
        }
    }
    return $false
}

# --- MAIN EXECUTION BLOCK ---
try {
    # Get current subscription
    $currentSubscription = Get-AzSubscription -SubscriptionId (Get-AzContext).Subscription.Id
    if (-not $currentSubscription) {
        Write-Error "Could not determine active subscription. Please select a subscription first using 'Set-AzContext -Subscription <ID>'"
        return
    }
   
    $SubId = $currentSubscription.Id
    $SubName = $currentSubscription.Name
    $CurrentScope = "/subscriptions/$SubId"
   
    Write-Host "Running setup for subscription: $SubName ($SubId)"

    # Part 1: Create the Service Principal and get its config
    $connectionConfig = Setup-XamOpsConnection -Scope $CurrentScope -SubscriptionId $SubId
   
    if (-not $connectionConfig) {
        Write-Error "Failed to create Service Principal. Aborting."
        return
    }

    # Part 2: Create the billing resources using the user's context
    # We pass the new Service Principal's ID to it so it can be granted access
    $storageId = Setup-CostExports -Scope $CurrentScope -PrincipalId $connectionConfig.principal_id
   
    if (-not $storageId) {
        Write-Error "Failed to set up cost exports."
        return
    }

    # Add storage info to the final JSON for the user
    $connectionConfig["billing_storage_account_id"] = $storageId
    $connectionConfig["billing_resource_group"] = "xamops-billing-rg"

    # --- Final Output ---
    Write-Host ""
    Write-Host "=========================================================================="
    Write-Host "✅ XamOps Connection Setup Complete"
    Write-Host "Service Principal has 'Reader' access to the subscription."
    Write-Host "Service Principal has 'Storage Blob Data Reader' access to the new billing storage account."
    Write-Host "Billing resources have been created in 'xamops-billing-rg' resource group."
    Write-Host "=========================================================================="
    Write-Host ""
    Write-Host "COPY THE JSON CONFIGURATION BELOW and paste it into the XamOps UI:"
    Write-Host ""
   
    # Output the final JSON to the console
    Write-Host ($connectionConfig | ConvertTo-Json -Depth 5)
   
    # Also save to file
    $configFile = "xamops-connection-final.json"
    $connectionConfig | ConvertTo-Json -Depth 5 | Out-File -FilePath $configFile
    Write-Host "Configuration also saved to $configFile"

} catch {
    Write-Error "An unexpected error occurred during main execution: $_.Exception.Message"
}