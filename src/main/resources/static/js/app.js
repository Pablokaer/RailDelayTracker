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

document.addEventListener('DOMContentLoaded', () => {
    const path = window.location.pathname;
    document.querySelectorAll('[data-nav-path]').forEach(link => {
        link.classList.toggle('active', link.dataset.navPath === path);
    });
    initSidebar();

    const sidebarClock = document.querySelector('.sidebar-clock');
    if (sidebarClock) sidebarClock.textContent = clockText();
});

/**
 * Sidebar behaviour. On desktop it is a persistent column that collapses to an icon rail
 * (preference remembered; with no saved preference, medium screens start collapsed so the
 * content keeps its width). Below 992px it is an off-canvas drawer opened from the header.
 */
function initSidebar() {
    const sidebar = document.getElementById('app-sidebar');
    const collapse = document.querySelector('.sidebar-toggle');
    const opener = document.querySelector('.nav-open');
    const closer = document.querySelector('.nav-close');
    const backdrop = document.getElementById('sidebar-backdrop');
    if (!sidebar) return;

    const STORAGE_KEY = 'ierail-sidebar-collapsed';
    const desktop = window.matchMedia('(min-width: 992px)');
    const read = () => { try { return localStorage.getItem(STORAGE_KEY); } catch (e) { return null; } };
    const write = (value) => { try { localStorage.setItem(STORAGE_KEY, value); } catch (e) { /* private mode */ } };

    const setCollapsed = (collapsed) => {
        document.body.classList.toggle('sidebar-collapsed', collapsed);
        if (!collapse) return;
        const label = collapsed ? 'Expand navigation' : 'Collapse navigation';
        collapse.setAttribute('aria-expanded', String(!collapsed));
        collapse.setAttribute('aria-label', label);
        collapse.title = label;
        const text = collapse.querySelector('span');
        if (text) text.textContent = collapsed ? 'Expand' : 'Collapse';
    };
    const setDrawer = (open) => {
        sidebar.classList.toggle('open', open);
        if (backdrop) backdrop.classList.toggle('show', open);
        if (opener) {
            opener.setAttribute('aria-expanded', String(open));
            opener.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
        }
        if (open) {
            const first = sidebar.querySelector('.sidebar-nav a');
            if (first) first.focus();
        } else if (opener && !desktop.matches && document.activeElement && sidebar.contains(document.activeElement)) {
            opener.focus();
        }
    };
    const applyMode = () => {
        setDrawer(false);
        if (desktop.matches) {
            const saved = read();
            setCollapsed(saved === null ? window.innerWidth < 1280 : saved === 'true');
        } else {
            document.body.classList.remove('sidebar-collapsed');
        }
    };

    if (collapse) collapse.addEventListener('click', () => {
        const collapsed = !document.body.classList.contains('sidebar-collapsed');
        setCollapsed(collapsed);
        write(String(collapsed));
    });
    if (opener) opener.addEventListener('click', () => setDrawer(!sidebar.classList.contains('open')));
    if (closer) closer.addEventListener('click', () => setDrawer(false));
    if (backdrop) backdrop.addEventListener('click', () => setDrawer(false));
    sidebar.addEventListener('click', (event) => {
        if (event.target.closest('a') && !desktop.matches) setDrawer(false);
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && sidebar.classList.contains('open')) setDrawer(false);
    });
    desktop.addEventListener('change', applyMode);
    applyMode();
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
