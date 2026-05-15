/**
 * OmegaRelay Provider — KSU WebUI logic.
 *
 * The WebUI is a thin shell over the daemon: we read/write a single config
 * file and a couple of status files via root shell exec.
 */
(function () {
    "use strict";

    var CONFIG = "/data/adb/omega/provider.conf";
    var LOG = "/data/adb/omega/logs/provider.log";
    var PID = "/data/adb/omega/provider.pid";

    // ---- DOM helpers ----
    function $(id) { return document.getElementById(id); }
    function setHint(el, msg, cls) {
        el.textContent = msg || "";
        el.className = "hint" + (cls ? " " + cls : "");
    }
    function setStatus(text, cls) {
        var s = $("status");
        s.textContent = text;
        s.className = "status" + (cls ? " " + cls : "");
    }

    // ---- Config parsing ----
    function parseConfig(text) {
        var out = {};
        text.split(/\r?\n/).forEach(function (line) {
            var trimmed = line.replace(/#.*$/, "").trim();
            if (!trimmed) return;
            var eq = trimmed.indexOf("=");
            if (eq < 0) return;
            out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
        });
        return out;
    }
    function buildConfig(values) {
        return [
            "# OmegaRelay Provider configuration. Edit via WebUI or text editor.",
            "",
            "url = " + (values.url || ""),
            "device_id = " + (values.device_id || ""),
            "psk = " + (values.psk || ""),
            "tls_insecure = " + (values.tls_insecure ? "true" : "false"),
            ""
        ].join("\n");
    }

    // ---- Shell helpers ----
    function readFile(path) {
        return Ksu.exec("cat " + shQuote(path) + " 2>/dev/null").then(function (r) {
            return r.errno === 0 ? r.stdout : null;
        });
    }
    function writeFile(path, content) {
        // base64 to avoid quoting nightmares.
        var b64 = btoa(unescape(encodeURIComponent(content)));
        var cmd =
            "mkdir -p $(dirname " + shQuote(path) + ") && " +
            "echo " + shQuote(b64) + " | base64 -d > " + shQuote(path) + " && " +
            "chmod 600 " + shQuote(path);
        return Ksu.exec(cmd);
    }
    function shQuote(s) {
        return "'" + String(s).replace(/'/g, "'\\''") + "'";
    }

    // ---- Status checks ----
    function checkStatus() {
        return Promise.all([
            readFile(PID),
            // The JVM runs as `dalvikvm64`. Distinguish ours by matching the
            // command line which contains our entry class.
            Ksu.exec(
                "for f in /proc/[0-9]*/cmdline; do " +
                "  tr '\\0' ' ' < $f 2>/dev/null | grep -q 'org.ommega.relay.provider.App' && " +
                "    echo \"$(basename $(dirname $f))\" && break; " +
                "done"
            ),
            Ksu.exec("getprop sys.boot_completed"),
        ]).then(function (results) {
            var pid = results[0] ? results[0].trim() : "";
            var foundPid = (results[1].stdout || "").trim();
            var booted = (results[2].stdout || "").trim() === "1";

            $("daemon_pid").textContent = foundPid || pid || "—";

            if (!booted) {
                $("daemon_status").textContent = "boot incomplete";
                setStatus("booting", "warn");
                return;
            }
            if (foundPid) {
                $("daemon_status").textContent = "running";
                setStatus("running", "good");
            } else {
                $("daemon_status").textContent = "not running";
                setStatus("stopped", "bad");
            }
        });
    }

    function loadLog() {
        return Ksu.exec("tail -c 8192 " + shQuote(LOG) + " 2>/dev/null").then(function (r) {
            $("log").textContent = r.stdout && r.stdout.trim()
                ? r.stdout
                : "(log empty — daemon may not have started yet)";
        });
    }

    function loadConfig() {
        return readFile(CONFIG).then(function (text) {
            if (!text) return;
            var v = parseConfig(text);
            $("url").value = v.url || "";
            $("device_id").value = v.device_id || "";
            $("psk").value = v.psk || "";
            $("tls_insecure").checked = (v.tls_insecure || "").toLowerCase() === "true";
        });
    }

    function saveAndRestart() {
        var hint = $("save_hint");
        var url = $("url").value.trim();
        var deviceId = $("device_id").value.trim();
        var psk = $("psk").value;
        var tlsInsecure = $("tls_insecure").checked;

        if (!/^wss?:\/\//.test(url)) {
            setHint(hint, "URL must start with ws:// or wss://", "bad");
            return;
        }
        if (psk.length < 16) {
            setHint(hint, "PSK must be at least 16 characters", "bad");
            return;
        }
        if (!deviceId) {
            setHint(hint, "Device ID is required", "bad");
            return;
        }

        setHint(hint, "saving…", "");

        var content = buildConfig({
            url: url, device_id: deviceId, psk: psk, tls_insecure: tlsInsecure,
        });

        writeFile(CONFIG, content).then(function (r) {
            if (r.errno !== 0) {
                setHint(hint, "save failed: " + (r.stderr || r.stdout || ("errno=" + r.errno)), "bad");
                return;
            }
            // Best-effort restart: kill the JVM, the daemon.sh wrapper restarts it.
            return Ksu.exec("pkill -f 'org.ommega.relay.provider' 2>/dev/null; true");
        }).then(function () {
            setHint(hint, "saved. daemon restarting…", "good");
            setTimeout(checkStatus, 2000);
            setTimeout(loadLog, 3000);
        }).catch(function (e) {
            setHint(hint, "error: " + e, "bad");
        });
    }

    function restart() {
        Ksu.exec("pkill -f 'org.ommega.relay.provider' 2>/dev/null; true").then(function () {
            setTimeout(checkStatus, 2000);
            setTimeout(loadLog, 3000);
        });
    }

    function clearLog() {
        Ksu.exec(": > " + shQuote(LOG)).then(function () {
            $("log").textContent = "(cleared)";
        });
    }

    // ---- Initial load ----
    function refreshAll() {
        return Promise.all([loadConfig(), checkStatus(), loadLog()]);
    }

    if (!Ksu.isAvailable()) {
        setStatus("KSU API unavailable", "warn");
    }

    document.addEventListener("DOMContentLoaded", function () {
        $("save").addEventListener("click", saveAndRestart);
        $("reload").addEventListener("click", refreshAll);
        $("refresh").addEventListener("click", function () {
            Promise.all([checkStatus(), loadLog()]);
        });
        $("restart").addEventListener("click", restart);
        $("clear_log").addEventListener("click", clearLog);
        refreshAll();
        // Periodic status refresh.
        setInterval(checkStatus, 5000);
    });
})();
