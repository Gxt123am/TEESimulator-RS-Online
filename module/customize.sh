# shellcheck disable=SC2034
SKIPUNZIP=1
MIN_SDK=29
CONFIG_DIR=/data/adb/tricky_store

# --- Installation Context Check ---
if [ "$BOOTMODE" != true ]; then
  ui_print "! Please install in Magisk Manager or KernelSU Manager"
  abort "! Install from recovery is NOT supported"
fi

if [ "$KSU" = true ] && [ "$KSU_VER_CODE" -lt 10670 ]; then
  abort "! Please update your KernelSU and KernelSU Manager"
fi

# --- Version Info ---
VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Installing TEESimulator-RS $VERSION"
ui_print ""

# --- Architecture Handling ---
case "$ARCH" in
  arm64) ABI_DIR="arm64-v8a" ;;
  arm)   ABI_DIR="armeabi-v7a" ;;
  x64)   ABI_DIR="x86_64" ;;
  x86)   ABI_DIR="x86" ;;
  *)     abort "! Unsupported architecture: $ARCH" ;;
esac

ui_print "- Device platform: $ARCH"
ui_print "- Using ABI dir: $ABI_DIR"

# --- SDK Check ---
if [ "$API" -lt "$MIN_SDK" ]; then
  abort "! Unsupported SDK: $API. Minimum required is $MIN_SDK"
else
  ui_print "- Device SDK: $API"
fi
ui_print ""

# --- Helper to install files ---
install_file() {
  if ! unzip -qqjo "$ZIPFILE" "$1" -d "$2"; then
    abort "! Failed to extract $1"
  fi
  ui_print "- Extracted $1"
}

# --- Installation ---
ui_print "- Extracting module files"
for file in customize.sh module.prop service.sh sepolicy.rule daemon action.sh uninstall.sh omega-relay.example.conf; do
  install_file "$file" "$MODPATH"
done

# Handle service.apk or classes.dex
if unzip -l "$ZIPFILE" | grep -q "service.apk"; then
  install_file "service.apk" "$MODPATH"
elif unzip -l "$ZIPFILE" | grep -q "classes.dex"; then
  install_file "classes.dex" "$MODPATH"
else
  abort "! Neither service.apk nor classes.dex found"
fi

chmod 755 "$MODPATH/daemon"
ui_print ""

ui_print "- Extracting $ARCH libraries"
install_file "lib/$ABI_DIR/libTEESimulator.so" "$MODPATH"
install_file "lib/$ABI_DIR/libinject.so" "$MODPATH"
install_file "lib/$ABI_DIR/libsupervisor.so" "$MODPATH"
install_file "lib/$ABI_DIR/libcertgen.so" "$MODPATH"
ui_print ""

mv "$MODPATH/libinject.so" "$MODPATH/inject"
mv "$MODPATH/libsupervisor.so" "$MODPATH/supervisor"
chmod 755 "$MODPATH/inject"
chmod 755 "$MODPATH/supervisor"

# --- Configuration Files ---
if [ ! -d "$CONFIG_DIR" ]; then
  ui_print "- Creating configuration directory"
  mkdir -p "$CONFIG_DIR"
fi

if [ ! -f "$CONFIG_DIR/keybox.xml" ]; then
  ui_print "- Adding AOSP software keybox"
  install_file "keybox.xml" "$CONFIG_DIR"
fi

if [ ! -f "$CONFIG_DIR/target.txt" ]; then
  ui_print "- Adding default target scope"
  install_file "target.txt" "$CONFIG_DIR"
fi

# Drop a relay config template into the config dir on first install
# so the user can edit it in place. We never overwrite an existing
# omega-relay.conf — that would clobber the operator's secrets.
if [ ! -f "$CONFIG_DIR/omega-relay.conf" ] && [ ! -f "$CONFIG_DIR/omega-relay.example.conf" ]; then
  ui_print "- Adding omega-relay.example.conf template"
  install_file "omega-relay.example.conf" "$CONFIG_DIR"
  ui_print "  (rename to omega-relay.conf and fill in url/psk/device_id)"
fi

rm -f "$CONFIG_DIR/tee_status.txt"

if [ ! -f "$CONFIG_DIR/hbk" ]; then
  ui_print "- Generating device-unique hardware-bound key seed"
  head -c 32 /dev/random > "$CONFIG_DIR/hbk"
fi
