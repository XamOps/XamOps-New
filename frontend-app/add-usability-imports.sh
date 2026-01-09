#!/bin/bash

# Script to add usability improvement imports to all HTML files
# Run this from the frontend-app directory

echo "Adding usability improvement imports to HTML files..."

# List of main pages to update (excluding fragments, templates, and already updated files)
pages=(
    "finops.html"
    "performance.html"
    "security.html"
    "waste.html"
    "cost.html"
    "cloudlist.html"
    "cloudmap.html"
    "cloudk8s.html"
    "cloudshell.html"
    "reservation.html"
    "calculator.html"
    "gcp_dashboard.html"
    "gcp_finops.html"
    "gcp_performance.html"
    "gcp_security.html"
    "gcp_waste.html"
    "gcp_cost.html"
    "gcp_cloudlist.html"
    "gcp_alerts.html"
    "gcp_rightsizing.html"
    "azure_dashboard.html"
    "azure_cloudlist.html"
    "complianceops.html"
    "dataops.html"
    "aiops.html"
    "account-manager.html"
    "user-manager.html"
    "actions-history.html"
    "xamops_tickets.html"
    "xamops_ticket_detail.html"
    "unified-dashboard.html"
)

# The import lines to add
IMPORT_LINES='
    <!-- Usability Improvements -->
    <link rel="stylesheet" href="/src/usability-improvements.css">
    <script type="module" src="/src/usability-utils.js"></script>
'

count=0
skipped=0

for page in "${pages[@]}"; do
    if [ -f "$page" ]; then
        # Check if imports already exist
        if grep -q "usability-improvements.css" "$page"; then
            echo "✓ $page - Already has imports (skipped)"
            ((skipped++))
        else
            # Find the line with </head> and insert before it
            if grep -q "</head>" "$page"; then
                # Create a temp file with the imports added
                awk -v imports="$IMPORT_LINES" '
                    /<\/head>/ {
                        print imports
                    }
                    { print }
                ' "$page" > "$page.tmp"

                # Replace original file
                mv "$page.tmp" "$page"
                echo "✅ $page - Imports added"
                ((count++))
            else
                echo "⚠️  $page - No </head> tag found (skipped)"
                ((skipped++))
            fi
        fi
    else
        echo "⚠️  $page - File not found (skipped)"
        ((skipped++))
    fi
done

echo ""
echo "=========================================="
echo "Summary:"
echo "  ✅ Updated: $count files"
echo "  ⚠️  Skipped: $skipped files"
echo "=========================================="
echo ""
echo "Done! Please review the changes and test the pages."
