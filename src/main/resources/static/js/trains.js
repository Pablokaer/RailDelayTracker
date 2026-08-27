/* ═══════════════════════════════════════
   LIVE BOARD LOGIC
═══════════════════════════════════════ */
let activeFilter = 'all';
let refreshTimer;
let countdown    = 30;
let currentStation = document.getElementById('station-selector')?.value || 'CNLLY';

/* Navigation lives in /js/app.js as changeView(). */

document.getElementById('filter-bar').addEventListener('click', e => {
    const btn = e.target.closest('.filter-btn[data-filter]');
    if (!btn) return;
    document.querySelectorAll('.filter-btn[data-filter]').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    activeFilter = btn.dataset.filter;
    applyFilter();
});

function applyFilter() {
    const rows = document.querySelectorAll('#train-tbody tr[data-dir]');
    let visible = 0;
    rows.forEach(row => {
        const dir  = (row.dataset.dir || '').toLowerCase();
        const late = parseInt(row.dataset.late || '0', 10);
        let show =
            activeFilter === 'all'     ||
            (activeFilter === 'north'   && dir.includes('north')) ||
            (activeFilter === 'south'   && dir.includes('south')) ||
            (activeFilter === 'delayed' && late >= _DELAYED_MIN);
        row.style.display = show ? '' : 'none';
        if (show) visible++;
    });
    const rc = document.getElementById('row-count');
    if (rc) rc.textContent = `Showing ${visible} train${visible !== 1 ? 's' : ''}`;
}

function delayBadge(late) {
    let cat = _CAT_DATA[0];
    for (let i = _CAT_DATA.length - 1; i >= 0; i--) {
        if (late >= _CAT_DATA[i].minMinutes) { cat = _CAT_DATA[i]; break; }
    }
    const label = cat.isOnTime ? 'On time' : `+${late} min`;
    return `<span style="background:${cat.bgColor};color:${cat.textColor};border:1px solid ${cat.borderColor};border-radius:5px;padding:2px 8px;font-size:.75rem">${label}</span>`;
}

function renderRow(t) {
    const dir = t.direction === 'Northbound'
        ? `<span class="dir-north"><i class="bi bi-arrow-up-short"></i>N</span>`
        : t.direction === 'Southbound'
        ? `<span class="dir-south"><i class="bi bi-arrow-down-short"></i>S</span>`
        : `<span style="color:var(--text-muted);font-size:.8rem">${t.direction || '—'}</span>`;
    const due = t.dueIn === 0 ? `<span class="due-now">Now</span>`
        : t.dueIn <= 5 ? `<span class="due-soon">${t.dueIn} min</span>`
        : `<span class="due-later">${t.dueIn} min</span>`;
    const del = delayBadge(t.late);
    const loc = t.locationType === 'O' ? `<span class="loc-origin">Origin</span>`
        : t.locationType === 'D' ? `<span class="loc-dest">Destination</span>`
        : t.locationType === 'S' ? `<span class="loc-stop">Stop</span>`
        : `<span style="color:var(--text-muted);font-size:.78rem">${t.locationType || ''}</span>`;
    const last = (t.lastLocation && t.lastLocation.trim())
        ? `<span data-tooltip="${t.lastLocation}" style="color:var(--text-muted);font-size:.8rem">${t.lastLocation}</span>`
        : `<span style="color:var(--border)">—</span>`;
    return `<tr data-dir="${t.direction||''}" data-late="${t.late}">
        <td><span class="train-chip">${t.trainCode||'—'}</span></td>
        <td style="color:var(--text-muted);font-size:.78rem">${t.trainType||'—'}</td>
        <td>${t.origin||'—'}</td><td>${t.destination||'—'}</td>
        <td>${dir}</td><td>${t.schDepart||'—'}</td><td>${t.expDepart||'—'}</td>
        <td>${t.schArrival||'—'}</td><td>${t.expArrival||'—'}</td>
        <td>${due}</td><td><span class="badge-status">${t.status||'—'}</span></td>
        <td>${del}</td>
        <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${last}</td>
        <td>${loc}</td>
    </tr>`;
}

async function fetchTrains() {
    const btn = document.getElementById('refresh-btn');
    btn.innerHTML = '<i class="bi bi-arrow-clockwise me-1 spin"></i>Loading…';
    try {
        const res = await fetch('/api/trains?stationCode=' + currentStation);
        if (!res.ok) throw new Error(res.statusText);
        const trains = await res.json();
        const tbody = document.getElementById('train-tbody');
        tbody.innerHTML = trains.length === 0
            ? `<tr><td colspan="14"><div class="empty-state"><i class="bi bi-train-front-fill d-block mb-3"></i><div class="fw-semibold mb-1">No trains found</div></div></td></tr>`
            : trains.map(renderRow).join('');
        document.getElementById('clock').textContent =
            new Date().toLocaleTimeString('en-IE', { hour12: false });
        const delayed = trains.filter(t => t.late >= _DELAYED_MIN).length;
        const svs = document.querySelectorAll('.stat-value');
        numUpdate(svs[0], trains.length);
        numUpdate(svs[1], trains.length - delayed);
        numUpdate(svs[2], delayed);
        applyFilter();
        staggerRows(document.getElementById('train-tbody'));
        resetCountdown();
    } catch (err) {
        console.error('Fetch failed:', err);
    } finally {
        btn.innerHTML = '<i class="bi bi-arrow-clockwise me-1"></i>Refresh now';
    }
}

function resetCountdown() {
    clearInterval(refreshTimer);
    countdown = 30;
    const bar = document.getElementById('refresh-bar');
    bar.style.animation = 'none'; bar.offsetHeight; bar.style.animation = '';
    refreshTimer = setInterval(() => {
        countdown--;
        const el = document.getElementById('countdown-stat');
        if (el) el.textContent = countdown;
        if (countdown <= 0) fetchTrains();
    }, 1000);
}


/* ═══════════════════════════════════════
   TAB SWITCHING
═══════════════════════════════════════ */
let chartsReady = false;
let _chartOntime, _chartHourly, _chartCats, _chartDest;

function showTab(tab) {
    const isLive = tab === 'live';
    document.getElementById('tab-live').style.display      = isLive ? '' : 'none';
    document.getElementById('tab-analytics').style.display = isLive ? 'none' : '';
    document.querySelectorAll('.tab-nav-btn').forEach(b => b.classList.remove('active'));
    document.querySelector(`.tab-nav-btn[data-tab="${tab}"]`).classList.add('active');
    if (!isLive && !chartsReady) {
        initCharts(); chartsReady = true;
        document.querySelectorAll('.a-stat-val').forEach(el => countUp(el));
        animateBars();
        ['station-rank-tbody','top10-tbody','dest-table-tbody'].forEach(id => staggerRows(document.getElementById(id)));
    }
}


/* ═══════════════════════════════════════
   CHART.JS — DARK THEME DEFAULTS
═══════════════════════════════════════ */
Chart.defaults.color       = '#5d7a99';
Chart.defaults.borderColor = '#1e2d3d';
Chart.defaults.font.family = "'Segoe UI', system-ui, sans-serif";

const GRID  = { color: '#1e2d3d' };
const TICKS = { color: '#5d7a99' };

function makeCtx(id) {
    const canvas = document.getElementById(id);
    return canvas ? canvas.getContext('2d') : null;
}

function initCharts() {
    // 1. On Time vs Delayed — doughnut
    const ctxO = makeCtx('chart-ontime');
    if (ctxO && (_onTime + _delayed) > 0) {
        _chartOntime = new Chart(ctxO, {
            type: 'doughnut',
            data: {
                labels: ['On Time', 'Delayed'],
                datasets: [{
                    data: [_onTime, _delayed],
                    backgroundColor: ['rgba(62,207,115,.7)', 'rgba(248,113,113,.7)'],
                    borderColor:     ['#3ecf73', '#f87171'],
                    borderWidth: 2,
                    hoverOffset: 6
                }]
            },
            options: {
                responsive: true, maintainAspectRatio: true,
                cutout: '62%',
                plugins: {
                    legend: { position: 'bottom', labels: { color: '#5d7a99', padding: 14, font: { size: 12 } } },
                    tooltip: { callbacks: { label: c => ` ${c.label}: ${c.parsed} trains` } }
                }
            }
        });
    }

    // 2. Hourly — dual-line (delay% + avg delay)
    const ctxH = makeCtx('chart-hourly');
    if (ctxH && _hLabels.length > 0) {
        _chartHourly = new Chart(ctxH, {
            type: 'line',
            data: {
                labels: _hLabels,
                datasets: [
                    {
                        label: 'Delay Probability (%)',
                        data: _hDelayPcts,
                        yAxisID: 'yPct',
                        borderColor: '#f87171',
                        backgroundColor: 'rgba(248,113,113,.12)',
                        fill: true, tension: 0.35, pointRadius: 4,
                        pointBackgroundColor: '#f87171'
                    },
                    {
                        label: 'Avg Delay (min)',
                        data: _hAvgDelays,
                        yAxisID: 'yMin',
                        borderColor: '#f59e0b',
                        backgroundColor: 'rgba(245,158,11,.08)',
                        fill: true, tension: 0.35, pointRadius: 4,
                        pointBackgroundColor: '#f59e0b',
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

    // 3. Delay Categories — doughnut (severity tiers from enum)
    const ctxC = makeCtx('chart-categories');
    if (ctxC && (_catOnTime + _catSmall + _catMedium + _catBig + _catExtreme) > 0) {
        _chartCats = new Chart(ctxC, {
            type: 'doughnut',
            data: {
                labels: _CAT_DATA.map(c => c.displayLabel),
                datasets: [{
                    data: [_catOnTime, _catSmall, _catMedium, _catBig, _catExtreme],
                    backgroundColor: _CAT_DATA.map(c => c.bgColor),
                    borderColor:     _CAT_DATA.map(c => c.textColor),
                    borderWidth: 2,
                    hoverOffset: 6
                }]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                cutout: '58%',
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: c => ` ${c.label}: ${c.parsed} trips` } }
                }
            }
        });
    }

    // 4. Destinations — horizontal bar
    const ctxD = makeCtx('chart-destinations');
    if (ctxD && _dLabels.length > 0) {
        _chartDest = new Chart(ctxD, {
            type: 'bar',
            data: {
                labels: _dLabels,
                datasets: [
                    {
                        label: 'Avg Delay (min)',
                        data: _dAvgDelays,
                        backgroundColor: 'rgba(245,158,11,.6)',
                        borderColor: '#f59e0b',
                        borderWidth: 1, borderRadius: 4
                    },
                    {
                        label: 'Delay Count',
                        data: _dCounts,
                        backgroundColor: 'rgba(248,113,113,.45)',
                        borderColor: '#f87171',
                        borderWidth: 1, borderRadius: 4
                    }
                ]
            },
            options: {
                indexAxis: 'y',
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { labels: { color: '#5d7a99', font: { size: 11 } } } },
                scales: {
                    x: { grid: GRID, ticks: TICKS },
                    y: { grid: GRID, ticks: { color: '#e8f0f8', font: { size: 11 } } }
                }
            }
        });
    }
}

/* ═══════════════════════════════════════
   ANALYTICS AUTO-REFRESH
═══════════════════════════════════════ */
let _analyticsRefreshing = false;

async function refreshAnalytics() {
    if (_analyticsRefreshing) return;
    _analyticsRefreshing = true;
    try {
        const p = new URLSearchParams(window.location.search);
        p.delete('stationCode');
        const url = '/api/analytics/summary' + (p.toString() ? '?' + p : '');
        const res = await fetch(url);
        if (!res.ok) return;
        _applyAnalytics(await res.json());
    } catch (e) { console.error('Analytics refresh:', e); }
    finally { _analyticsRefreshing = false; }
}

function _setText(id, v) { const e = document.getElementById(id); if (e) e.textContent = v; }

function _applyAnalytics(d) {
    const db = d.dashboard;

    // Section 1 — summary cards
    numUpdate(document.getElementById('a-total-snaps'),  db.totalSnapshots);
    numUpdate(document.getElementById('a-avg-delay'),    db.averageDelay, ' min');
    numUpdate(document.getElementById('a-max-delay'),    db.maxDelay, ' min');
    numUpdate(document.getElementById('a-unique-trips'), db.uniqueTrips);
    numUpdate(document.getElementById('a-delay-prob'),   db.delayProbability, '%');
    const pc = document.getElementById('a-delay-prob-card');
    if (pc) { pc.style.borderColor = db.delayProbabilityColor + '44'; pc.querySelector('.a-stat-val').style.color = db.delayProbabilityColor; }

    // Section 2 — probability hero
    const ph = document.getElementById('prob-hero');
    if (ph) { numUpdate(ph, db.delayProbability, '%'); ph.style.color = db.delayProbabilityColor; }
    numUpdate(document.getElementById('prob-delayed'),  db.delayedTrips, ' delayed');
    _setText('prob-of-trips', 'of ' + db.uniqueTrips + ' trips');
    numUpdate(document.getElementById('prob-ontime'),   db.onTimeTrips, ' on time');
    const pf = document.getElementById('prob-fill');
    if (pf) { pf.style.width = db.delayProbability + '%'; pf.style.background = db.delayProbabilityColor; }

    // Section 3 — station ranking
    const srTb = document.getElementById('station-rank-tbody');
    if (srTb) srTb.innerHTML = _renderStationRankRows(d.stationRank || []);

    // Section 5 — top 10 + destinations
    const t10Tb = document.getElementById('top10-tbody');
    if (t10Tb) t10Tb.innerHTML = _renderTop10Rows(d.top10Delays || []);
    const destTb = document.getElementById('dest-table-tbody');
    if (destTb) destTb.innerHTML = _renderDestRows(d.destinations || []);

    // Section 6 — category bars + breakdown
    const catBarsCol = document.getElementById('cat-bars-col');
    if (catBarsCol) catBarsCol.innerHTML = _renderCatBars(d.categories || {}, d.maxCatCount || 1);
    const catBdCol = document.getElementById('cat-breakdown-col');
    if (catBdCol) catBdCol.innerHTML = _renderCatBreakdown(d.categories || {}, db.delayedTrips);

    // Charts
    if (_chartOntime) { _chartOntime.data.datasets[0].data = [db.onTimeTrips, db.delayedTrips]; _chartOntime.update(); }
    if (_chartHourly && d.hourlyLabels && d.hourlyLabels.length) {
        _chartHourly.data.labels = d.hourlyLabels;
        _chartHourly.data.datasets[0].data = d.hourlyDelayPcts;
        _chartHourly.data.datasets[1].data = d.hourlyAvgDelays;
        _chartHourly.update();
    }
    if (_chartCats) { _chartCats.data.datasets[0].data = [d.catOnTime, d.catSmall, d.catMedium, d.catBig, d.catExtreme]; _chartCats.update(); }
    if (_chartDest && d.destLabels && d.destLabels.length) {
        _chartDest.data.labels = d.destLabels;
        _chartDest.data.datasets[0].data = d.destAvgDelays;
        _chartDest.data.datasets[1].data = d.destDelayCounts;
        _chartDest.update();
    }

    // Animate updated rows and bars
    staggerRows(document.getElementById('station-rank-tbody'));
    staggerRows(document.getElementById('top10-tbody'));
    staggerRows(document.getElementById('dest-table-tbody'));
    animateBars();

    // Refresh badge
    const rb = document.getElementById('a-refreshed-at');
    const rt = document.getElementById('a-refreshed-time');
    if (rb && rt) { rb.style.display = ''; rt.textContent = new Date().toLocaleTimeString('en-IE', { hour12: false }); }
}

const _MEDALS = ['🥇','🥈','🥉'];
function _renderStationRankRows(rows) {
    if (!rows.length) return '<tr><td colspan="6" class="text-center py-4" style="color:var(--text-muted)">No station data yet</td></tr>';
    return rows.map((s, i) => `<tr>
        <td>${i < 3 ? `<span style="font-size:1rem">${_MEDALS[i]}</span>` : `<span style="color:var(--text-muted);font-size:.82rem">${i+1}</span>`}</td>
        <td class="fw-semibold">${s.stationName}</td>
        <td>${s.averageDelay > 0 ? `<span class="badge-late">${s.averageDelay} min</span>` : '<span class="badge-ontime">On time</span>'}</td>
        <td><div class="d-flex align-items-center gap-2">
            <span class="rank-bar-track"><span class="rank-bar-fill d-block" style="width:${s.delayProbability}%;background:${s.delayProbabilityBarColor};height:100%"></span></span>
            <span style="font-size:.82rem;min-width:38px">${s.delayProbability}%</span>
        </div></td>
        <td style="color:#f87171">${s.delayedTrips}</td>
        <td style="color:var(--text-muted)">${s.totalTrips}</td>
    </tr>`).join('');
}

function _renderTop10Rows(rows) {
    if (!rows.length) return '<tr><td colspan="6" class="text-center py-4" style="color:var(--text-muted)">No delayed records</td></tr>';
    return rows.map((r, i) => `<tr>
        <td style="color:var(--text-muted);font-size:.82rem">${i+1}</td>
        <td><span class="train-chip">${r.trainCode||'—'}</span></td>
        <td style="font-size:.78rem"><span style="color:var(--text-primary)">${r.stationFullName||'—'}</span>${r.destination ? ` <span style="color:var(--text-muted)">→ ${r.destination}</span>` : ''}</td>
        <td style="color:var(--text-muted);font-size:.78rem">${r.schDepart||'—'}</td>
        <td style="color:var(--text-muted);font-size:.78rem">${r.schArrival||'—'}</td>
        <td><span class="badge-late fw-bold">+${r.peakDelayMinutes} min</span></td>
    </tr>`).join('');
}

function _renderDestRows(rows) {
    if (!rows.length) return '<tr><td colspan="4" class="text-center py-4" style="color:var(--text-muted)">No destination data yet</td></tr>';
    return rows.map(d => `<tr>
        <td style="font-size:.82rem">${d.destination||'—'}</td>
        <td><span class="badge-late">${d.avgDelay} min</span></td>
        <td style="color:#f87171;font-size:.82rem">${d.delayCount}</td>
        <td style="color:var(--text-muted);font-size:.82rem">${d.totalCount}</td>
    </tr>`).join('');
}

function _catColor(key) { return _CAT_DATA.find(c => c.displayLabel === key)?.textColor || '#60a5fa'; }

function _renderCatBars(cats, maxCount) {
    const title = `<div style="font-size:.72rem;text-transform:uppercase;letter-spacing:.08em;color:var(--text-muted);margin-bottom:1rem">Distribution of delay severity</div>`;
    const bars = Object.entries(cats).map(([key, val]) => {
        const pct = maxCount > 0 ? (val * 100 / maxCount) : 0;
        return `<div class="mb-3">
            <div class="d-flex align-items-center gap-2 mb-1">
                <span style="font-size:.82rem;font-weight:600;min-width:70px">${key}</span>
                <span style="font-size:.78rem;color:var(--text-muted)">${val} trips</span>
            </div>
            <div class="cat-track"><div class="cat-fill" style="width:${pct}%;background:${_catColor(key)}">${val > 0 ? val : ''}</div></div>
        </div>`;
    }).join('');
    return title + bars;
}

function _renderCatBreakdown(cats, delayedTrips) {
    const title = `<div style="font-size:.72rem;text-transform:uppercase;letter-spacing:.08em;color:var(--text-muted);margin-bottom:1rem">Category breakdown</div>`;
    const rows = Object.entries(cats).map(([key, val]) => {
        const pct = delayedTrips > 0 ? (val * 100 / delayedTrips).toFixed(1) + '%' : '0%';
        return `<div class="d-flex justify-content-between align-items-center mb-2 py-1" style="border-bottom:1px solid rgba(30,45,61,.5)">
            <span style="font-size:.85rem">${key}</span>
            <div class="d-flex align-items-center gap-3">
                <span class="badge-late" style="font-size:.8rem">${val}</span>
                <span style="font-size:.75rem;color:var(--text-muted);min-width:40px;text-align:right">${pct}</span>
            </div>
        </div>`;
    }).join('');
    return title + rows + `<div class="mt-3 pt-2 d-flex justify-content-between align-items-center" style="border-top:1px solid var(--border)">
        <span style="font-size:.82rem;color:var(--text-muted)">Total delayed trips</span>
        <span class="badge-late fw-bold">${delayedTrips}</span>
    </div>`;
}

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
    document.querySelectorAll('.rank-bar-fill,.prob-fill,.cat-fill').forEach(el => {
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
    const els = document.querySelectorAll('.stat-card,.a-stat-card,.chart-card,.board-wrap');
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

/* Boot */
const _params = new URLSearchParams(window.location.search);
const _startAnalytics = _params.has('from') || _params.has('to');
if (_startAnalytics) showTab('analytics');
applyFilter();
resetCountdown();
if (!_startAnalytics) {
    document.querySelectorAll('.stat-value').forEach(el => countUp(el));
    staggerRows(document.getElementById('train-tbody'));
}
setupReveal();

(function connectSSE() {
    const es = new EventSource('/api/events');
    es.addEventListener('snapshot', () => {
        clearInterval(refreshTimer);
        fetchTrains();
        refreshAnalytics();
    });
    es.onerror = () => { es.close(); setTimeout(connectSSE, 5000); };
})();
