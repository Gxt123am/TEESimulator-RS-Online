/**
 * Thin wrapper around the KernelSU / Magisk WebUI shell-exec API.
 *
 * KSU (and recent Magisk WebUI) injects a `ksu` global with `exec(cmd, opts, cb)`.
 * For non-KSU contexts we fall back to a stub that prints to console so the
 * UI still renders during development.
 */
(function () {
    "use strict";

    var hasKsu = typeof window !== "undefined" && typeof window.ksu === "object" && window.ksu;
    var hasMmrl = typeof window !== "undefined" && typeof window.$MMRL !== "undefined";

    /**
     * Execute a shell command with root, returning a Promise<{stdout, stderr, errno}>.
     * Commands are quoted by the caller (we don't try to be smart).
     */
    function exec(cmd) {
        return new Promise(function (resolve, reject) {
            if (hasKsu && typeof window.ksu.exec === "function") {
                // KernelSU API: ksu.exec(cmd, options, callback)
                try {
                    window.ksu.exec(cmd, "{}", function (errno, stdout, stderr) {
                        resolve({ errno: errno, stdout: stdout || "", stderr: stderr || "" });
                    });
                } catch (e) {
                    reject(e);
                }
                return;
            }
            // Fallback for browser preview.
            console.log("[stub-exec]", cmd);
            resolve({ errno: 1, stdout: "", stderr: "ksu API unavailable" });
        });
    }

    function isAvailable() { return hasKsu; }

    // Expose
    window.Ksu = { exec: exec, isAvailable: isAvailable };
})();
