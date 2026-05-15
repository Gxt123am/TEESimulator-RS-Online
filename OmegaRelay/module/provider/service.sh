#!/system/bin/sh
# OmegaRelay Provider — boot-time service (post-fs-data).
#
# Magisk/KSU runs this with root. We launch a daemon shell which keeps the Java
# process alive across crashes.

MODDIR=${0%/*}
CONFIG_DIR="/data/adb/omega"

# Keep boot fast: don't block here. Spawn the daemon in the background.
( "$MODDIR/daemon.sh" "$MODDIR" "$CONFIG_DIR" >/dev/null 2>&1 ) &
