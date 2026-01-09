#!/bin/bash

# Safe script to add usability improvement imports to HTML files
# Uses sed instead of awk for better string handling

cd /Users/apple/Desktop/Release-XamOps/XamOps-New/frontend-app

echo "Adding usability improvement imports to HTML files..."
echo ""

# List of files to update
files=(
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
    "xamops_tickets.html"
    "xamops_ticket_detail.html"
    "unified-dashboard.html"
)

count=0
skipped=0
errors=0

for file in "${files[@]}"; do
    if [ ! -f "$file" ]; then
        echo "⚠️  $file - File not found (skipped)"
        ((skipped++))
        continue
    fi

    # Check if imports already exist
    if grep -q "usability-improvements.css" "$file"; then
        echo "✓ $file - Already has imports (skipped)"
        ((skipped++))
        continue
    fi

    # Check if </head> exists
    if ! grep -q "</head>" "$file"; then
        echo "⚠️  $file - No </head> tag found (skipped)"
        ((skipped++))
        continue
    fi

    # Get original file size for validation
    original_size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)

    # Use sed to insert before </head> tag
    sed -i.backup '/<\/head>/i\
    <!-- Usability Improvements -->\
    <link rel="stylesheet" href="/src/usability-improvements.css">\
    <script type="module" src="/src/usability-utils.js">
' "$file"

    # Verify the file wasn't corrupted (check size is larger)
    new_size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)

    if [ "$new_size" -le "$original_size" ]; then
        echo "❌ $file - ERROR: File size decreased! Restoring backup..."
        mv "$file.backup" "$file"
        ((errors++))
    else
        echo "✅ $file - Imports added successfully"
        rm -f "$file.backup"
        ((count++))
    fi
done

echo ""
echo "=========================================="
echo "Summary:"
echo "  ✅ Updated: $count files"
echo "  ⚠️  Skipped: $skipped files"
echo "  ❌ Errors: $errors files"
echo "=========================================="
echo ""

if [ $count -gt 0 ]; then
    echo "✅ Success! Please test the pages to verify everything works."
else
    echo "⚠️  No files were updated. Please review the output above."
fi
