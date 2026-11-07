#!/bin/sh
# Installs Bir Nefes into Chromium so it cannot be removed or disabled from the browser.
# Run with sudo. To undo: sudo rm /etc/chromium/policies/managed/birnefes.json
set -eu
cat > /etc/chromium/policies/managed/birnefes.json <<EOF
{
  "ExtensionSettings": {
    "fohapjjlilnlhnpakacmhbmebpodcoop": {
      "installation_mode": "force_installed",
      "update_url": "https://birnefes.zerohidz.com/updates.xml"
    }
  },
  "IncognitoModeAvailability": 1,
  "BrowserGuestModeEnabled": false
}
EOF
chmod 644 /etc/chromium/policies/managed/birnefes.json
echo "Tamam. Chromium'u tamamen kapatıp aç."
