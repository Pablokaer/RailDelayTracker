/* ═══════════════════════════════════════════════════════════════════════════
   IERailMetrics — shared helpers
   Every page reimplemented these; the escaping one only existed on the journey
   planner, which is why the map was interpolating API strings raw.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Escapes a value for safe interpolation into an innerHTML template. */
function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (char) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[char]));
}

/** Header station selector: routes to a page or to a station's live board. */
function changeView(code) {
    if (!code) return;
    switch (code) {
        case 'MAP':      window.location.href = '/map'; return;
        case 'JOURNEY':  window.location.href = '/journey'; return;
        case 'OVERVIEW': window.location.href = '/overview'; return;
        default:         window.location.href = '/get?stationCode=' + encodeURIComponent(code);
    }
}

/** "HH:MM:SS" in 24h form, for the "Updated" stamp in the header. */
function clockText(date) {
    return (date || new Date()).toLocaleTimeString('en-IE', {
        hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
}

/**
 * Polls `task` every `intervalMs`, but only while the tab is visible, and backs off
 * exponentially while it keeps failing. Returns a handle with `.stop()`.
 *
 * A bare setInterval kept hammering the server from background tabs and retried a
 * failing endpoint at full rate.
 */
function startPolling(task, intervalMs, options) {
    const opts = options || {};
    const maxBackoff = opts.maxBackoffMs || intervalMs * 12;
    let timer = null;
    let failures = 0;
    let stopped = false;

    function delay() {
        if (failures === 0) return intervalMs;
        return Math.min(maxBackoff, intervalMs * Math.pow(2, failures));
    }

    function schedule() {
        clearTimeout(timer);
        if (stopped) return;
        timer = setTimeout(run, delay());
    }

    async function run() {
        if (stopped) return;
        if (document.visibilityState === 'hidden') { schedule(); return; }
        try {
            await task();
            failures = 0;
        } catch (error) {
            failures = Math.min(failures + 1, 5);
            console.error('Polling task failed:', error);
        }
        schedule();
    }

    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && !stopped) {
            failures = 0;
            run();
        }
    });

    run();
    return { stop() { stopped = true; clearTimeout(timer); } };
}

/** fetch with an abort timeout, so a hung request cannot stall the poll loop. */
async function fetchJson(url, timeoutMs) {
    const controller = new AbortController();
    const abort = setTimeout(() => controller.abort(), timeoutMs || 10000);
    try {
        const response = await fetch(url, { cache: 'no-store', signal: controller.signal });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return await response.json();
    } finally {
        clearTimeout(abort);
    }
}
