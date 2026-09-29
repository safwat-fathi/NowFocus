#!/bin/bash

# Ensure we're running as root
if [ "$EUID" -ne 0 ]; then
  echo "Please run as root (sudo ./install_daemon_local.sh)"
  exit 1
fi

echo "Finding latest build in DerivedData..."
DERIVED_DATA_APP=$(find /Users/safwat/Library/Developer/Xcode/DerivedData/NowFocus-* -path "*/Build/Products/Debug/NowFocus.app" -not -path "*/Index.noindex/*" -type d -print -quit)

if [ -z "$DERIVED_DATA_APP" ]; then
    echo "Could not find NowFocus.app in DerivedData. Please build it in Xcode first."
    exit 1
fi

echo "Found app at: $DERIVED_DATA_APP"

DAEMON_SRC="$DERIVED_DATA_APP/Contents/Library/LaunchDaemons/NowFocusDaemon"
FRAMEWORK_SRC="$DERIVED_DATA_APP/Contents/Frameworks/NowFocusCore.framework"

if [ ! -f "$DAEMON_SRC" ]; then
    echo "Daemon not found in app bundle!"
    exit 1
fi

echo "Copying daemon and framework to system locations..."
mkdir -p /usr/local/bin
cp "$DAEMON_SRC" /usr/local/bin/NowFocusDaemon

# We copy the framework to /Library/Frameworks so it's outside the user's home dir,
# avoiding permissions issues for a root daemon.
rm -rf /Library/Frameworks/NowFocusCore.framework
cp -r "$FRAMEWORK_SRC" /Library/Frameworks/

echo "Fixing framework dependency path to absolute system path..."
install_name_tool -change "@rpath/NowFocusCore.framework/Versions/A/NowFocusCore" "/Library/Frameworks/NowFocusCore.framework/Versions/A/NowFocusCore" /usr/local/bin/NowFocusDaemon

# Resign the modified binary ad-hoc
codesign --force --sign - /usr/local/bin/NowFocusDaemon

echo "Creating and loading LaunchDaemon plist..."
cat << 'EOF' > /Library/LaunchDaemons/com.getnowfocus.daemon.plist
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.getnowfocus.daemon</string>
    <key>Program</key>
    <string>/usr/local/bin/NowFocusDaemon</string>
    <key>MachServices</key>
    <dict>
        <key>app.getnowfocus.daemon</key>
        <true/>
    </dict>
</dict>
</plist>
EOF

launchctl unload /Library/LaunchDaemons/com.getnowfocus.daemon.plist 2>/dev/null || true
launchctl load -w /Library/LaunchDaemons/com.getnowfocus.daemon.plist

echo "Daemon successfully installed and loaded via launchctl!"
echo "You can now test the app."
