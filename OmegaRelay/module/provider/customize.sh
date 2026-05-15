# Magisk / KernelSU installer customization script.
#
# Refs:
#   https://topjohnwu.github.io/Magisk/guides.html#customizing-installation
#   https://kernelsu.org/guide/module.html

SKIPUNZIP=0   # let Magisk unzip module.prop, classes.dex etc. for us

# --- Sanity checks ---
if [ "$BOOTMODE" != "true" ]; then
  ui_print "! Install via Magisk Manager / KernelSU Manager — not from recovery."
  abort "! Aborting"
fi

API_REQUIRED=29
if [ "$API" -lt "$API_REQUIRED" ]; then
  abort "! Android API $API_REQUIRED+ required (found $API)"
fi

ui_print "- OmegaRelay Provider $(grep_prop version "$TMPDIR/module.prop")"
ui_print "- Device API: $API"
ui_print "- Architecture: $ARCH"

# --- Layout ---
CONFIG_DIR="/data/adb/omega"
mkdir -p "$CONFIG_DIR" 2>/dev/null
chmod 700 "$CONFIG_DIR" 2>/dev/null
chown 0:0 "$CONFIG_DIR" 2>/dev/null

# Drop in a config template if none exists. The user can edit it via the WebUI
# or with a text editor. Daemon won't start until config is valid.
if [ ! -f "$CONFIG_DIR/provider.conf" ]; then
  ui_print "- Installing default config template at $CONFIG_DIR/provider.conf"
  cp -f "$MODPATH/provider.example.conf" "$CONFIG_DIR/provider.conf"
  chmod 600 "$CONFIG_DIR/provider.conf"
fi

# --- Permissions on module-installed files ---
set_perm "$MODPATH/service.sh" 0 0 0700
set_perm "$MODPATH/daemon.sh"  0 0 0700
set_perm "$MODPATH/classes.dex" 0 0 0644

ui_print "- Installation complete. Reboot to start the provider."
ui_print "- Edit config: $CONFIG_DIR/provider.conf (chmod 600)"
ui_print "- Logs:         $CONFIG_DIR/logs/provider.log"
