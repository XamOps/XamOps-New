#!/bin/bash

echo "========================================="
echo "🔧 AutoSpotting API Key Fix"
echo "========================================="
echo ""

# The correct API key
CORRECT_KEY="sHX4wpgTYZhdBI8LxrM8lhkY8eFgsIg2"

echo "📋 Problem Identified:"
echo "  - Environment variable AUTOSPOTTING_API_KEY has an old/wrong key"
echo "  - This overrides the correct key in properties files"
echo "  - Current env var: ${AUTOSPOTTING_API_KEY:0:10}..."
echo ""

echo "✅ Solution:"
echo "  1. Update environment variable to correct key"
echo "  2. Restart backend service to pick up the change"
echo ""

# Update .zshrc to set the correct key permanently
echo "📝 Updating ~/.zshrc with correct API key..."
if grep -q "AUTOSPOTTING_API_KEY" ~/.zshrc 2>/dev/null; then
    echo "  - Found existing AUTOSPOTTING_API_KEY in ~/.zshrc"
    echo "  - Updating to correct value..."
    sed -i.bak "s/export AUTOSPOTTING_API_KEY=.*/export AUTOSPOTTING_API_KEY=\"$CORRECT_KEY\"/" ~/.zshrc
else
    echo "  - Adding AUTOSPOTTING_API_KEY to ~/.zshrc..."
    echo "" >> ~/.zshrc
    echo "# AutoSpotting API Key" >> ~/.zshrc
    echo "export AUTOSPOTTING_API_KEY=\"$CORRECT_KEY\"" >> ~/.zshrc
fi

echo "✅ ~/.zshrc updated!"
echo ""

# Set for current session
export AUTOSPOTTING_API_KEY="$CORRECT_KEY"
echo "✅ Environment variable updated for current session"
echo "   AUTOSPOTTING_API_KEY=${AUTOSPOTTING_API_KEY:0:10}..."
echo ""

echo "========================================="
echo "🚀 Next Steps:"
echo "========================================="
echo ""
echo "1. Stop the current backend service (Ctrl+C in the terminal running it)"
echo ""
echo "2. Restart the backend service:"
echo "   cd /Users/apple/Desktop/XamOps-New/xamops-service"
echo "   mvn spring-boot:run"
echo ""
echo "3. Watch the logs for:"
echo "   ✅ API Key configured: YES (length=32, first 10 chars: 'sHX4wpgTYZ')"
echo ""
echo "4. Test the API - it should now work!"
echo ""
echo "========================================="
echo "✅ Fix Complete!"
echo "========================================="
