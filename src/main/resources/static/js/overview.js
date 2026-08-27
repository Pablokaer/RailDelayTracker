/* ── Animation helpers ── */
function countUp(el, dur) {
    dur = dur || 800;
    const raw = el.textContent.trim();
    const m = raw.match(/^(\d+)(.*)/);
    if (!m || +m[1] === 0) return;
    const target = +m[1], suffix = m[2], t0 = performance.now();
    (function tick(now) {
        const p = Math.min((now - t0) / dur, 1);
        el.textContent = Math.round((1 - Math.pow(1-p,3)) * target) + suffix;
        if (p < 1) requestAnimationFrame(tick);
    })(t0);
}
function animateBars() {
    document.querySelectorAll('.rank-bar-fill').forEach(el => {
        const w = el.style.width; if (!w) return;
        el.style.transition = 'none'; el.style.width = '0';
        requestAnimationFrame(() => requestAnimationFrame(() => { el.style.transition = ''; el.style.width = w; }));
    });
}
function staggerRows(tbody) {
    if (!tbody) return;
    Array.from(tbody.querySelectorAll('tr')).forEach((r, i) => {
        r.style.animation = `rowSlideIn .22s ease ${i * 28}ms both`;
    });
}
function animateCards(sel, base) {
    document.querySelectorAll(sel).forEach((el, i) => {
        el.style.animation = `fadeSlideUp .35s ease ${(base||0) + i * 55}ms both`;
    });
}
function setupReveal() {
    const els = document.querySelectorAll('.a-stat-card,.chart-card,.board-wrap');
    let pending = [], timer;
    const obs = new IntersectionObserver(entries => {
        entries.forEach(e => { if (e.isIntersecting) { pending.push(e.target); obs.unobserve(e.target); } });
        clearTimeout(timer);
        timer = setTimeout(() => {
            pending.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
            pending.forEach((el, i) => setTimeout(() => el.classList.add('visible'), i * 65));
            pending = [];
        }, 20);
    }, { threshold: 0.07, rootMargin: '0px 0px -24px 0px' });
    els.forEach(el => obs.observe(el));
}
function numUpdate(el, newVal, suffix) {
    if (!el) return;
    suffix = suffix || '';
    const to = +newVal;
    const m = (el.textContent || '').trim().match(/^(\d+)/);
    const from = m ? +m[1] : 0;
    if (from === to) return;
    const dur = 420, t0 = performance.now();
    (function tick(now) {
        const p = Math.min((now - t0) / dur, 1);
        el.textContent = Math.round(from + (1 - Math.pow(1-p,3)) * (to - from)) + suffix;
        if (p < 1) requestAnimationFrame(tick);
    })(t0);
}

/* ── Station navigation ──────────────────────────────────────────────── */
function switchScope(code, clickedTab) {
    const p = new URLSearchParams(window.location.search);
    p.delete('stationCode');
    if (code !== 'OVERVIEW') p.set('stationCode', code);

    const nextUrl = '/overview' + (p.toString() ? '?' + p : '');
    loadOverviewUrl(nextUrl, clickedTab, 'scope').catch(() => { window.location.href = nextUrl; });
}

function changeStation(code) {
    if (code === 'MAP') {
        window.location.href = '/map';
        return;
    }
    if (code === 'JOURNEY') {
        window.location.href = '/journey';
        return;
    }
    const tab = document.querySelector(`.scope-tab[data-scope="${code}"]`);
    switchScope(code, tab);
}

/* ── Station search ──────────────────────────────────────────────────── */
function filterStations(q) {
    const term = q.toLowerCase();
    document.querySelectorAll('#station-tbody tr[data-name]').forEach(row => {
        row.style.display = row.dataset.name.includes(term) ? '' : 'none';
    });
}

/* ── Station table sort ──────────────────────────────────────────────── */
let sortCol = -1, sortAsc = true;

function sortTable(col) {
    const tbody = document.getElementById('station-tbody');
    const rows  = Array.from(tbody.querySelectorAll('tr[data-name]'));
    sortAsc = sortCol === col ? !sortAsc : true;
    sortCol = col;

    document.querySelectorAll('#station-table thead th').forEach((th, i) => {
        th.classList.remove('sort-asc', 'sort-desc');
        if (i === col) th.classList.add(sortAsc ? 'sort-asc' : 'sort-desc');
    });

    rows.sort((a, b) => {
        const av = a.cells[col].textContent.trim().replace(/[^0-9.]/g, '') || a.cells[col].textContent.trim();
        const bv = b.cells[col].textContent.trim().replace(/[^0-9.]/g, '') || b.cells[col].textContent.trim();
        const an = parseFloat(av), bn = parseFloat(bv);
        const cmp = (!isNaN(an) && !isNaN(bn)) ? an - bn : av.localeCompare(bv);
        return sortAsc ? cmp : -cmp;
    });
    rows.forEach(r => tbody.appendChild(r));
}

/* ── Chart.js defaults ───────────────────────────────────────────────── */
Chart.defaults.color       = '#5d7a99';
Chart.defaults.borderColor = '#1e2d3d';
Chart.defaults.font.family = "'Segoe UI', system-ui, sans-serif";
const GRID  = { color: '#1e2d3d' };
const TICKS = { color: '#5d7a99' };

/* ── Hourly line chart ───────────────────────────────────────────────── */
let _ovHourlyChart, _ovDestChart;
const ctxH = document.getElementById('chart-hourly');
if (ctxH && _hLabels.length > 0) {
    _ovHourlyChart = new Chart(ctxH, {
        type: 'line',
        data: {
            labels: _hLabels,
            datasets: [
                {
                    label: 'Delay Probability (%)',
                    data: _hDelayPcts, yAxisID: 'yPct',
                    borderColor: '#f87171', backgroundColor: 'rgba(248,113,113,.12)',
                    fill: true, tension: 0.35, pointRadius: 4, pointBackgroundColor: '#f87171'
                },
                {
                    label: 'Avg Delay (min)',
                    data: _hAvgDelays, yAxisID: 'yMin',
                    borderColor: '#f59e0b', backgroundColor: 'rgba(245,158,11,.08)',
                    fill: true, tension: 0.35, pointRadius: 4, pointBackgroundColor: '#f59e0b',
                    borderDash: [5, 3]
                }
            ]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: { legend: { labels: { color: '#5d7a99', font: { size: 11 } } } },
            scales: {
                x:    { grid: GRID, ticks: TICKS },
                yPct: { grid: GRID, ticks: { ...TICKS, callback: v => v + '%' }, position: 'left',  beginAtZero: true },
                yMin: { grid: { display: false }, ticks: { ...TICKS, callback: v => v + ' m' }, position: 'right', beginAtZero: true }
            }
        }
    });
}

/* ── Destinations horizontal bar ─────────────────────────────────────── */
const ctxD = document.getElementById('chart-destinations');
if (ctxD && _dLabels.length > 0) {
    _ovDestChart = new Chart(ctxD, {
        type: 'bar',
        data: {
            labels: _dLabels,
            datasets: [
                {
                    label: 'Avg Delay (min)',
                    data: _dAvgDelays,
                    backgroundColor: 'rgba(245,158,11,.6)', borderColor: '#f59e0b',
                    borderWidth: 1, borderRadius: 4
                },
                {
                    label: 'Delay Count',
                    data: _dCounts,
                    backgroundColor: 'rgba(248,113,113,.45)', borderColor: '#f87171',
                    borderWidth: 1, borderRadius: 4
                }
            ]
        },
        options: {
            indexAxis: 'y', responsive: true, maintainAspectRatio: false,
            plugins: { legend: { labels: { color: '#5d7a99', font: { size: 11 } } } },
            scales: {
                x: { grid: GRID, ticks: TICKS },
                y: { grid: GRID, ticks: { color: '#e8f0f8', font: { size: 11 } } }
            }
        }
    });
}

/* ── Delay categories bar chart (delays only) ────────────────────────── */
let _ovCatChart;
const ctxC = document.getElementById('chart-categories');
if (ctxC && (_catSmall + _catMedium + _catBig + _catExtreme) > 0) {
    const _delayOnly = _CAT_DATA.slice(1);
    _ovCatChart = new Chart(ctxC, {
        type: 'bar',
        data: {
            labels: _delayOnly.map(c => c.displayLabel),
            datasets: [{
                data: [_catSmall, _catMedium, _catBig, _catExtreme],
                backgroundColor: _delayOnly.map(c => c.bgColor),
                borderColor:     _delayOnly.map(c => c.textColor),
                borderWidth: 2, borderRadius: 6
            }]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: { label: c => ` ${c.parsed} trips` } }
            },
            scales: {
                x: { grid: GRID, ticks: TICKS },
                y: { grid: GRID, ticks: { ...TICKS }, beginAtZero: true }
            }
        }
    });
}

/* ── Auto-refresh (every 60 s) ───────────────────────────────────────── */
let _ovRefreshing = false;
let _periodNavigationPending = false;
let _overviewAbortController = null;
let _overviewRequestSeq = 0;

async function fetchOverviewPayload(search, signal) {
    const res = await fetch('/api/analytics/overview' + (search ? '?' + search : ''), { signal });
    if (!res.ok) throw new Error(res.statusText);
    return res.json();
}

async function refreshOverview() {
    if (_ovRefreshing || _periodNavigationPending) return;
    _ovRefreshing = true;
    try {
        const p = new URLSearchParams(window.location.search);
        const d = await fetchOverviewPayload(p.toString());
        _applyOverview(d);
    } catch (e) { console.error('Overview refresh:', e); }
    finally { _ovRefreshing = false; }
}

async function loadOverviewUrl(url, clickedButton, loadingType) {
    const target = new URL(url, window.location.origin);
    const nextPath = target.pathname + target.search;
    if (nextPath === window.location.pathname + window.location.search) return;

    if (_overviewAbortController) _overviewAbortController.abort();
    const requestId = ++_overviewRequestSeq;
    _overviewAbortController = new AbortController();

    const previous = window.location.href;
    _periodNavigationPending = true;
    window.history.pushState({}, '', target.pathname + target.search);
    setOverviewLoading(clickedButton, true, loadingType);
    try {
        const d = await fetchOverviewPayload(target.searchParams.toString(), _overviewAbortController.signal);
        if (requestId !== _overviewRequestSeq) return;
        _applyOverview(d);
        updateOverviewControls(target.searchParams, clickedButton);
    } catch (e) {
        if (e.name === 'AbortError') return;
        window.history.pushState({}, '', previous);
        throw e;
    } finally {
        if (requestId === _overviewRequestSeq) {
            setOverviewLoading(clickedButton, false, loadingType);
            _periodNavigationPending = false;
            _overviewAbortController = null;
        }
    }
}

function setOverviewLoading(btn, loading, loadingType) {
    setScopeLoading(btn, loading, loadingType === 'scope');
    setPeriodLoading(btn, loading, loadingType === 'period');
}

function setScopeLoading(btn, loading, isScopeLoading) {
    document.querySelectorAll('.scope-tab').forEach(tab => {
        tab.style.pointerEvents = loading ? 'none' : '';
        tab.style.opacity = loading ? '.65' : '';
    });
    const selector = document.getElementById('station-selector');
    if (selector) selector.disabled = loading;

    if (!isScopeLoading || !btn) return;
    btn.classList.toggle('loading', loading);
    const label = btn.querySelector('span:last-child');
    if (label) {
        if (loading) {
            btn.dataset.originalLabel = label.textContent;
            label.textContent = 'Loading...';
        } else if (btn.dataset.originalLabel) {
            label.textContent = btn.dataset.originalLabel;
            delete btn.dataset.originalLabel;
        }
    }
}

function setPeriodLoading(btn, loading, isPeriodLoading) {
    document.querySelectorAll('[data-period-filter], [data-period-apply]').forEach(el => {
        el.style.pointerEvents = loading ? 'none' : '';
        el.style.opacity = loading ? '.65' : '';
        if ('disabled' in el) el.disabled = loading;
    });

    document.querySelectorAll('.date-input').forEach(el => {
        el.disabled = loading;
    });

    if (isPeriodLoading && btn) {
        btn.classList.toggle('loading', loading);
        const label = btn.querySelector('.period-label');
        if (label) {
            if (loading) {
                btn.dataset.originalLabel = label.textContent;
                label.textContent = 'Loading...';
            } else if (btn.dataset.originalLabel) {
                label.textContent = btn.dataset.originalLabel;
                delete btn.dataset.originalLabel;
            }
        }
    }
}

function updateOverviewControls(params, activeButton) {
    updateScopeControls(params.get('stationCode') || 'OVERVIEW');
    updatePeriodControls(params, activeButton);
}

function updateScopeControls(code) {
    const selectedCode = code && code !== 'OVERVIEW' ? code : 'OVERVIEW';
    const selector = document.getElementById('station-selector');
    const selectedName = selector
        ? (selector.querySelector(`option[value="${selectedCode}"]`)?.textContent || selectedCode)
        : selectedCode;
    const isStation = selectedCode !== 'OVERVIEW';

    if (selector) selector.value = selectedCode;
    const hidden = document.getElementById('period-station-code');
    if (hidden) hidden.value = selectedCode;

    document.querySelectorAll('.scope-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.scope === selectedCode);
    });

    const title = document.getElementById('page-title');
    if (title) title.textContent = isStation ? `Overview — ${selectedName}` : 'Overview — All Stations';

    const summaryTitle = document.getElementById('overview-summary-title');
    if (summaryTitle) summaryTitle.textContent = isStation ? `Station Summary — ${selectedName}` : 'Network Summary';
}

function updatePeriodControls(params, activeButton) {
    document.querySelectorAll('[data-period-filter]').forEach(btn => btn.classList.remove('active'));
    const activePeriodButton = activeButton && activeButton.matches('[data-period-filter]')
        ? activeButton
        : resolveActivePeriodButton(params);
    if (activePeriodButton) activePeriodButton.classList.add('active');

    const from = params.get('from') || '';
    const to = params.get('to') || '';
    const fromInput = document.querySelector('input[name="from"]');
    const toInput = document.querySelector('input[name="to"]');
    if (fromInput) fromInput.value = from;
    if (toInput) toInput.value = to;

    updateActivePeriodLabel(from, to);
}

function resolveActivePeriodButton(params) {
    const period = params.get('period') || '';
    const from = params.get('from') || '';
    const to = params.get('to') || '';
    const today = isoDateOffset(0);
    const yesterday = isoDateOffset(-1);
    const last30From = isoDateOffset(-29);

    if (period.toLowerCase() === 'all') return document.querySelector('[data-period-filter="all"]');
    if (!from && !to) return document.querySelector('[data-period-filter="today"]');
    if (from === today && to === today) return document.querySelector('[data-period-filter="today"]');
    if (from === yesterday && to === yesterday) return document.querySelector('[data-period-filter="yesterday"]');
    if (from === last30From && to === today) return document.querySelector('[data-period-filter="last30"]');
    return null;
}

function isoDateOffset(offsetDays) {
    const d = new Date();
    d.setDate(d.getDate() + offsetDays);
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function updateActivePeriodLabel(from, to) {
    const label = document.getElementById('active-period-label');
    if (!label) return;
    if (!from && !to) {
        label.style.display = 'none';
        return;
    }
    const displayFrom = formatIsoDate(from);
    const displayTo = formatIsoDate(to || from);
    label.style.display = '';
    label.innerHTML = `<i class="bi bi-calendar3 me-1"></i>${displayFrom === displayTo ? displayFrom : `${displayFrom} â†’ ${displayTo}`}`;
}

function formatIsoDate(value) {
    if (!value) return '';
    const parts = value.split('-');
    if (parts.length !== 3) return value;
    return `${parts[2]}/${parts[1]}/${parts[0]}`;
}

function _ovSetText(id, v) { const e = document.getElementById(id); if (e) e.textContent = v; }

function _applyOverview(d) {
    const db = d.dashboard;

    // Summary cards
    numUpdate(document.getElementById('ov-total-snaps'),  db.totalSnapshots);
    numUpdate(document.getElementById('ov-unique-trips'), db.uniqueTrips);
    numUpdate(document.getElementById('ov-delayed'),      db.delayedTrips);
    numUpdate(document.getElementById('ov-ontime'),       db.onTimeTrips);
    numUpdate(document.getElementById('ov-avg-delay'),    db.averageDelay, ' min');
    numUpdate(document.getElementById('ov-max-delay'),    db.maxDelay, ' min');
    numUpdate(document.getElementById('ov-delay-prob'),   db.delayProbability, '%');
    const pc = document.getElementById('ov-delay-prob-card');
    if (pc) { pc.style.borderColor = db.delayProbabilityColor + '44'; pc.querySelector('.a-stat-val').style.color = db.delayProbabilityColor; }

    // Station table
    const tbody = document.getElementById('station-tbody');
    if (tbody) {
        const medals = ['🥇','🥈','🥉'];
        tbody.innerHTML = (d.stationRank || []).map((s, i) => `<tr data-name="${(s.stationName||'').toLowerCase()}">
            <td style="font-size:.8rem">${i < 3 ? `<span style="font-size:1rem">${medals[i]}</span>` : i+1}</td>
            <td><span style="color:var(--text-primary);font-weight:500">${s.stationName}</span>
                <span style="color:var(--text-muted);font-size:.72rem;margin-left:4px">${s.stationCode}</span></td>
            <td>${s.totalTrips}</td>
            <td style="color:#f87171">${s.delayedTrips}</td>
            <td style="color:#3ecf73">${s.totalTrips - s.delayedTrips}</td>
            <td><div class="d-flex align-items-center gap-2">
                <span class="rank-bar-track"><span class="rank-bar-fill d-block" style="width:${s.delayProbability}%;background:${s.delayProbabilityBarColor};height:100%"></span></span>
                <span style="font-size:.82rem;min-width:40px">${s.delayProbability}%</span>
            </div></td>
            <td>${s.averageDelay > 0 ? `<span class="badge-late">${s.averageDelay} min</span>` : '<span class="badge-ontime">On time</span>'}</td>
            <td style="color:#f59e0b;font-size:.82rem;white-space:nowrap">${s.totalAccumulatedDelay > 0 ? s.totalAccumulatedDelay + ' min' : '—'}</td>
        </tr>`).join('') || '<tr><td colspan="8" class="text-center py-4" style="color:var(--text-muted)">No data yet</td></tr>';
    }

    // Recent delays table
    const recentTb = document.getElementById('ov-recent-tbody');
    if (recentTb) recentTb.innerHTML = (d.recentDelays || []).map(s => `<tr>
        <td style="font-size:.78rem;white-space:nowrap;font-family:monospace">${s.capturedAt||'—'}</td>
        <td><span class="train-chip">${s.trainCode||'—'}</span></td>
        <td style="font-size:.78rem">${s.stationFullName||'—'}</td>
        <td style="font-size:.78rem">${s.origin||'—'}</td>
        <td style="font-size:.78rem">${s.destination||'—'}</td>
        <td><span class="badge-late fw-bold">+${s.lateMinutes} min</span></td>
    </tr>`).join('') || '<tr><td colspan="6" class="text-center py-4" style="color:var(--text-muted)">No delays captured yet</td></tr>';

    // Top 10 table
    const top10Tb = document.getElementById('ov-top10-tbody');
    if (top10Tb) top10Tb.innerHTML = (d.top10Delays || []).map((r, i) => `<tr>
        <td style="font-size:.78rem">${i+1}</td>
        <td><span class="train-chip">${r.trainCode||'—'}</span></td>
        <td style="font-size:.78rem">${r.trainDate||'—'}</td>
        <td style="font-size:.78rem">${r.origin||'—'}</td>
        <td style="font-size:.78rem">${r.stationFullName||'—'}</td>
        <td style="font-size:.78rem">${r.destination||'—'}</td>
        <td><span class="badge-late fw-bold">+${r.peakDelayMinutes} min</span></td>
        <td style="font-size:.78rem;white-space:nowrap">${r.capturedAt||'—'}</td>
    </tr>`).join('') || '<tr><td colspan="8" class="text-center py-4" style="color:var(--text-muted)">No delayed trips</td></tr>';

    const routeTb = document.getElementById('ov-route-tbody');
    if (routeTb) routeTb.innerHTML = (d.routeRanking || []).map((r, i) => `<tr>
        <td style="font-size:.78rem">${i + 1}</td>
        <td style="font-weight:500">${r.origin || 'â€”'}</td>
        <td><span>${r.destination || 'â€”'}</span></td>
        <td>${r.totalTrips}</td>
        <td style="color:#f87171">${r.delayedTrips}</td>
        <td><div class="d-flex align-items-center gap-2">
            <span class="rank-bar-track"><span class="rank-bar-fill d-block" style="width:${r.delayProbability}%;background:${r.delayProbabilityBarColor};height:100%"></span></span>
            <span style="font-size:.82rem;min-width:40px">${r.delayProbability}%</span>
        </div></td>
        <td><span class="badge-late">${r.averageDelay} min</span></td>
        <td style="color:#f59e0b;font-size:.82rem;white-space:nowrap">${r.totalAccumulatedDelay} min</td>
    </tr>`).join('') || '<tr><td colspan="8" class="text-center py-4" style="color:var(--text-muted)">No route data yet</td></tr>';

    // Charts
    if (_ovHourlyChart && d.hourlyLabels && d.hourlyLabels.length) {
        _ovHourlyChart.data.labels = d.hourlyLabels;
        _ovHourlyChart.data.datasets[0].data = d.hourlyDelayPcts;
        _ovHourlyChart.data.datasets[1].data = d.hourlyAvgDelays;
        _ovHourlyChart.update();
    }
    if (_ovDestChart && d.destLabels && d.destLabels.length) {
        _ovDestChart.data.labels = d.destLabels;
        _ovDestChart.data.datasets[0].data = d.destAvgDelays;
        _ovDestChart.data.datasets[1].data = d.destDelayCounts;
        _ovDestChart.update();
    }
    if (_ovCatChart) {
        _ovCatChart.data.datasets[0].data = [d.catSmall, d.catMedium, d.catBig, d.catExtreme];
        _ovCatChart.update();
    }
    ['ov-recent-tbody','ov-top10-tbody','station-tbody','ov-route-tbody'].forEach(id => staggerRows(document.getElementById(id)));
    animateBars();
}
(function connectSSE() {
    const es = new EventSource('/api/events');
    es.addEventListener('snapshot', refreshOverview);
    es.onerror = () => { es.close(); setTimeout(connectSSE, 5000); };
})();

// Boot animations — DOMContentLoaded garante que o station ranking (após os scripts) já está no DOM
document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.a-stat-val').forEach(el => countUp(el));
    animateBars();
    ['ov-recent-tbody','ov-top10-tbody','station-tbody'].forEach(id => staggerRows(document.getElementById(id)));
    setupReveal();
    setupFastPeriodFilters();
});

function setupFastPeriodFilters() {
    document.querySelectorAll('[data-period-filter]').forEach(btn => {
        btn.addEventListener('click', event => {
            event.preventDefault();
            const target = new URL(btn.href, window.location.origin);
            const current = new URLSearchParams(window.location.search);
            const currentScope = current.get('stationCode');
            if (currentScope && currentScope !== 'OVERVIEW') target.searchParams.set('stationCode', currentScope);
            else target.searchParams.delete('stationCode');
            loadOverviewUrl(target.pathname + target.search, btn, 'period').catch(() => { window.location.href = target.pathname + target.search; });
        });
    });

    const form = document.querySelector('.filter-period-bar form');
    if (form) {
        form.addEventListener('submit', event => {
            event.preventDefault();
            const params = new URLSearchParams(window.location.search);
            const station = form.querySelector('input[name="stationCode"]')?.value;
            const from = form.querySelector('input[name="from"]')?.value;
            const to = form.querySelector('input[name="to"]')?.value;
            params.delete('period');
            params.delete('from');
            params.delete('to');
            if (station && station !== 'OVERVIEW') params.set('stationCode', station);
            else params.delete('stationCode');
            if (from) params.set('from', from);
            if (to) params.set('to', to);
            loadOverviewUrl('/overview' + (params.toString() ? '?' + params : ''), form.querySelector('[data-period-apply]'), 'period')
                .catch(() => form.submit());
        });
    }
}
