#!/system/bin/sh
# Long-running daemon wrapper: loops, restarts the JVM if it dies.
#
# We start the JVM via `dalvikvm` rather than `app_process` because:
#  - On modified ART runtimes (notably ColorOS / OxygenOS 16) `app_process`
#    aborts during startReg before reaching our entry class.
#  - We don't need any of the Android UI framework that `app_process` boots up;
#    dalvikvm gives us a vanilla JVM with the standard library on the
#    bootclasspath, which is exactly what a network daemon needs.
#
# Args:
#   $1  module dir (contains classes.dex)
#   $2  config dir (contains provider.conf)

MODDIR="$1"
CONFIG_DIR="$2"

LOG_DIR="$CONFIG_DIR/logs"
LOG_FILE="$LOG_DIR/provider.log"
PID_FILE="$CONFIG_DIR/provider.pid"

mkdir -p "$LOG_DIR" 2>/dev/null
chmod 700 "$CONFIG_DIR" "$LOG_DIR" 2>/dev/null

# Wait for boot to finish so AndroidKeyStore is ready and the network stack is up.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Give Wi-Fi/data another moment to settle.
sleep 5

CONFIG_FILE="$CONFIG_DIR/provider.conf"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "$(date +'%F %T') no config at $CONFIG_FILE — install/configure via WebUI" >> "$LOG_FILE"
    while [ ! -f "$CONFIG_FILE" ]; do sleep 30; done
fi

# Locate dalvikvm. APEX path on Android 14+, fallback on older releases.
if [ -x /apex/com.android.art/bin/dalvikvm ]; then
    DALVIK=/apex/com.android.art/bin/dalvikvm
elif [ -x /system/bin/dalvikvm ]; then
    DALVIK=/system/bin/dalvikvm
else
    echo "$(date +'%F %T') ERROR: dalvikvm not found" >> "$LOG_FILE"
    exit 1
fi

# Restart loop with exponential backoff capped at 60s.
backoff=2
while true; do
    echo "$(date +'%F %T') starting JVM via $DALVIK" >> "$LOG_FILE"
    "$DALVIK" \
        -cp "$MODDIR/classes.dex" \
        org.ommega.relay.provider.App \
        "$CONFIG_FILE" \
        >> "$LOG_FILE" 2>&1 &

    APP_PID=$!
    echo "$APP_PID" > "$PID_FILE"
    wait "$APP_PID"
    EXIT=$?

    echo "$(date +'%F %T') JVM exited with $EXIT, restarting in ${backoff}s" >> "$LOG_FILE"

    # Trim log if it grows too big.
    if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE")" -gt 5242880 ]; then
        tail -c 1048576 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
    fi

    sleep "$backoff"
    backoff=$(( backoff * 2 ))
    if [ "$backoff" -gt 60 ]; then backoff=60; fi
done
