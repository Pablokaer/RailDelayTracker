/* ═══════════════════════════════════════════════════════════════════════════
   Live train map
   ═══════════════════════════════════════════════════════════════════════════ */

const IRELAND_BOUNDS = L.latLngBounds([51.25, -10.9], [55.6, -5.25]);

const map = L.map('train-map', {
    zoomControl: true,
    maxBounds: IRELAND_BOUNDS.pad(0.3),
    maxBoundsViscosity: 0.8,
    preferCanvas: true
}).setView([53.35, -7.8], 7);

L.tileLayer(TILE_URL, { attribution: TILE_ATTRIBUTION, maxZoom: 18 }).addTo(map);

// Physical rail geometry from OpenRailwayMap. Created lazily so no tiles are requested from a
// volunteer-run service until someone actually turns the layer on.
let railLayer = null;

// Route drawing sits below the train markers so the markers stay clickable on top of it.
const routeLayer   = L.layerGroup().addTo(map);
const trainLayer   = L.layerGroup().addTo(map);
const stationLayer = L.layerGroup();
let legLine = null;
let routeRequestToken = 0;

/** trainKey → { marker, train, animation } */
const markers = new Map();
/** Latest payload, keyed the same way, so filters re-render without refetching. */
let latestTrains = [];
let selectedKey = null;
let hasFitBounds = false;
let stationsLoaded = false;

const filters = { status: 'running', delayedOnly: false, query: '' };

/* ── keys ───────────────────────────────────────────────────────────────── */

/**
 * Identity of a train across refreshes. The old implementation folded the array
 * index into this key, so a single train dropping out of the feed shifted every
 * later key and the map destroyed and rebuilt all of its markers.
 */
function trainKey(train) {
    return `${train.trainCode || 'unknown'}|${train.trainDate || ''}`;
}

/* ── rendering helpers ──────────────────────────────────────────────────── */

function markerColor(train) {
    if (!train.running) return '#6b8aac';
    return train.delayColor || '#3ecf73';
}

function iconHtml(train) {
    const arrow = train.heading === null || train.heading === undefined
        ? '<div class="train-pin"></div>'
        : `<div class="train-arrow" style="transform:rotate(${train.heading}deg)"></div>`;
    const classes = ['train-dot'];
    if (!train.running) classes.push('not-running');
    return `<div class="${classes.join(' ')}" style="--marker-color:${markerColor(train)}">${arrow}</div>`;
}

function buildIcon(train) {
    return L.divIcon({
        className: 'train-marker',
        iconSize: [24, 24],
        iconAnchor: [12, 12],
        popupAnchor: [0, -12],
        html: iconHtml(train)
    });
}

/**
 * Updates an existing marker's visuals in place. Calling setIcon on every refresh
 * replaced the DOM node, which killed the rotation transition and made the whole
 * map flicker every 10 seconds.
 */
function applyVisual(marker, train, isSelected) {
    const root = marker.getElement();
    const dot = root && root.querySelector('.train-dot');
    if (!dot) {
        marker.setIcon(buildIcon(train));
        return;
    }

    dot.style.setProperty('--marker-color', markerColor(train));
    dot.classList.toggle('not-running', !train.running);
    dot.classList.toggle('selected', isSelected);

    const hasHeading = train.heading !== null && train.heading !== undefined;
    const arrow = dot.querySelector('.train-arrow');
    if (hasHeading && arrow) {
        arrow.style.transform = `rotate(${train.heading}deg)`;
    } else if (hasHeading !== !!arrow) {
        // Shape changed (dot ⇄ arrow) — a rebuild is the only way.
        marker.setIcon(buildIcon(train));
    }
}

/* ── smooth movement ────────────────────────────────────────────────────── */

const ANIMATION_MS = 900;
const moving = new Map();
// requestAnimationFrame rather than setInterval: one step per painted frame, in sync with the
// display, and the browser suspends it entirely while the tab is hidden — the interval kept
// running at 25 fps in the background.
let animationFrame = null;

/** Slides a marker to its new position instead of teleporting it. */
function moveMarker(key, marker, toLat, toLon) {
    const from = marker.getLatLng();
    if (from.lat === toLat && from.lng === toLon) return;

    // Off-screen markers do not need interpolation; skipping them keeps the
    // frame loop cheap when 150+ trains are in the feed.
    if (!map.getBounds().pad(0.2).contains([toLat, toLon])) {
        marker.setLatLng([toLat, toLon]);
        return;
    }

    moving.set(key, { marker, fromLat: from.lat, fromLon: from.lng, toLat, toLon, startedAt: performance.now() });
    if (animationFrame === null) animationFrame = requestAnimationFrame(stepAnimation);
}

function stepAnimation() {
    const now = performance.now();
    for (const [key, state] of moving.entries()) {
        const progress = Math.min(1, (now - state.startedAt) / ANIMATION_MS);
        const eased = 1 - Math.pow(1 - progress, 3);
        state.marker.setLatLng([
            state.fromLat + (state.toLat - state.fromLat) * eased,
            state.fromLon + (state.toLon - state.fromLon) * eased
        ]);
        if (progress >= 1) moving.delete(key);
    }
    if (moving.size === 0) {
        animationFrame = null;
        return;
    }
    animationFrame = requestAnimationFrame(stepAnimation);
}

/* ── popup ──────────────────────────────────────────────────────────────── */

function popupHtml(train) {
    const lines = [];
    if (train.origin || train.destination) {
        lines.push(`<div class="popup-line"><strong>${escapeHtml(train.origin || '?')}</strong> → <strong>${escapeHtml(train.destination || '?')}</strong></div>`);
    }
    if (train.nextStop) {
        lines.push(`<div class="popup-line">Next stop: <strong>${escapeHtml(train.nextStop)}</strong></div>`);
    }
    if (train.lastLocation) {
        lines.push(`<div class="popup-line">Last seen: ${escapeHtml(train.lastLocation)}</div>`);
    }
    if (train.scheduledDeparture) {
        lines.push(`<div class="popup-line">Scheduled departure: ${escapeHtml(train.scheduledDeparture)}</div>`);
    }
    if (train.expectedDeparture) {
        lines.push(`<div class="popup-line">Expected departure: ${escapeHtml(train.expectedDeparture)}</div>`);
    }
    lines.push(`<div class="popup-line">${escapeHtml(train.statusLabel || '')}${train.direction ? ' · ' + escapeHtml(train.direction) : ''}</div>`);
    lines.push(`<div class="popup-line">Heading: ${headingText(train)}</div>`);

    return `
        <div>
            <div class="popup-code">${escapeHtml(train.trainCode || 'Unknown train')}</div>
            <div class="popup-delay" style="color:${markerColor(train)}">${escapeHtml(train.delayLabel || '')}</div>
            ${lines.join('')}
            <button type="button" class="popup-action" data-history="${escapeHtml(train.trainCode || '')}">
                <i class="bi bi-clock-history me-1"></i>Delay history
            </button>
        </div>`;
}

/** Being explicit about provenance matters: a guessed heading should look guessed. */
function headingText(train) {
    if (train.heading === null || train.heading === undefined) return 'not available';
    const degrees = Math.round(train.heading);
    const labels = {
        'movement':    'from movement',
        'next-stop':   'towards next stop',
        'destination': 'towards destination',
        'compass':     'reported direction',
        'previous':    'last known'
    };
    return `${degrees}° <span style="opacity:.7">(${escapeHtml(labels[train.headingSource] || train.headingSource || '')})</span>`;
}

/* ── map sync ───────────────────────────────────────────────────────────── */

function visibleTrains() {
    const query = filters.query.trim().toLowerCase();
    return latestTrains.filter(train => {
        if (filters.status === 'running' && !train.running) return false;
        if (filters.delayedOnly && !(train.lateMinutes !== null && train.lateMinutes >= DELAYED_MIN)) return false;
        if (query) {
            const haystack = [train.trainCode, train.origin, train.destination, train.nextStop]
                .filter(Boolean).join(' ').toLowerCase();
            if (!haystack.includes(query)) return false;
        }
        return true;
    });
}

function syncMarkers(trains) {
    const seen = new Set();
    const bounds = [];

    trains.forEach(train => {
        const key = trainKey(train);
        seen.add(key);
        bounds.push([train.latitude, train.longitude]);

        let entry = markers.get(key);
        if (!entry) {
            const marker = L.marker([train.latitude, train.longitude], {
                icon: buildIcon(train),
                keyboard: true,
                title: `${train.trainCode || ''} ${train.delayLabel || ''}`.trim()
            });
            marker.bindPopup(popupHtml(train), { closeButton: true, autoPan: false });
            marker.on('click', () => selectTrain(key, { fromMap: true }));
            marker.addTo(trainLayer);
            entry = { marker, train };
            markers.set(key, entry);
        } else {
            moveMarker(key, entry.marker, train.latitude, train.longitude);
            applyVisual(entry.marker, train, key === selectedKey);
            // setContent keeps an open popup open; bindPopup used to orphan it.
            entry.marker.getPopup().setContent(popupHtml(train));
            entry.marker.options.title = `${train.trainCode || ''} ${train.delayLabel || ''}`.trim();
            entry.train = train;
        }
    });

    for (const [key, entry] of markers.entries()) {
        if (seen.has(key)) continue;
        trainLayer.removeLayer(entry.marker);
        markers.delete(key);
        moving.delete(key);
        if (key === selectedKey) clearSelection();
    }

    if (!hasFitBounds && bounds.length > 0) {
        map.fitBounds(bounds, { padding: [40, 40], maxZoom: 9 });
        hasFitBounds = true;
    }
}

/* ── list ───────────────────────────────────────────────────────────────── */

function renderList(trains) {
    const list = document.getElementById('train-list');

    if (!trains.length) {
        list.innerHTML = `
            <div class="empty-state">
                <i class="bi bi-geo-alt fs-1 d-block mb-2"></i>
                No trains match the current filters.
            </div>`;
        return;
    }

    const sorted = [...trains].sort((a, b) => {
        const lateA = a.lateMinutes === null || a.lateMinutes === undefined ? -999 : a.lateMinutes;
        const lateB = b.lateMinutes === null || b.lateMinutes === undefined ? -999 : b.lateMinutes;
        if (lateA !== lateB) return lateB - lateA;
        return String(a.trainCode || '').localeCompare(String(b.trainCode || ''));
    });

    list.innerHTML = sorted.map(train => {
        const key = trainKey(train);
        const leg = [train.origin, train.destination].filter(Boolean).join(' → ')
            || train.direction || 'Route not reported';
        return `
            <button class="train-row${key === selectedKey ? ' selected' : ''}" type="button" data-key="${escapeHtml(key)}">
                <span>
                    <span class="train-code">${escapeHtml(train.trainCode || 'Unknown')}</span>
                    ${train.running ? '' : `<span class="status-tag">${escapeHtml(train.statusLabel || '')}</span>`}
                </span>
                <span class="train-delay" style="color:${markerColor(train)}">${escapeHtml(train.delayLabel || '')}</span>
                <span class="train-leg">${escapeHtml(leg)}</span>
                ${train.nextStop ? `<span class="train-next">Next: ${escapeHtml(train.nextStop)}</span>` : ''}
            </button>`;
    }).join('');
}

/* ── selection ──────────────────────────────────────────────────────────── */

function selectTrain(key, options) {
    const opts = options || {};
    const entry = markers.get(key);
    if (!entry) return;

    selectedKey = key;
    markers.forEach((other, otherKey) => applyVisual(other.marker, other.train, otherKey === key));
    renderList(visibleTrains());

    if (!opts.fromMap) {
        map.setView(entry.marker.getLatLng(), Math.max(map.getZoom(), 10), { animate: true });
        entry.marker.openPopup();
    }

    document.getElementById('history-panel').classList.add('visible');
    document.getElementById('history-title-code').textContent = (entry.train.trainCode || '').trim();
    showDrawerPane('route');
    loadRoute(entry.train);
    loadHistory(entry.train.trainCode);
}

function clearSelection() {
    selectedKey = null;
    clearRoute();
    document.getElementById('history-panel').classList.remove('visible');
}

function clearRoute() {
    routeLayer.clearLayers();
    if (legLine) { map.removeLayer(legLine); legLine = null; }
}

function showDrawerPane(pane) {
    document.querySelectorAll('.drawer-tab').forEach(t => t.classList.toggle('active', t.dataset.pane === pane));
    document.getElementById('route-body').style.display = pane === 'route' ? '' : 'none';
    document.getElementById('history-body').style.display = pane === 'history' ? '' : 'none';
}

/* ── route ──────────────────────────────────────────────────────────────── */

async function loadRoute(train) {
    const body = document.getElementById('route-body');
    body.innerHTML = '<div class="empty-state" style="padding:1.2rem"><i class="bi bi-arrow-clockwise spin"></i> Carregando rota…</div>';

    // A click on another train while this request is in flight must not draw the old route.
    const token = ++routeRequestToken;
    try {
        const params = new URLSearchParams({ trainDate: train.trainDate || '' });
        const route = await fetchJson(
            `/api/trains/${encodeURIComponent((train.trainCode || '').trim())}/route?${params}`, 12000);
        if (token !== routeRequestToken) return;

        const stops = route.stops || [];
        if (!stops.length) {
            body.innerHTML = '<div class="empty-state" style="padding:1.2rem">Sem rota publicada para este serviço.</div>';
            drawLeg(train);
            return;
        }
        drawRoute(route, train);
        renderRouteList(route, train);
    } catch (error) {
        if (token !== routeRequestToken) return;
        body.innerHTML = '<div class="empty-state" style="padding:1.2rem">Não foi possível carregar a rota.</div>';
        drawLeg(train);
    }
}

/** Index of the first stop still ahead of the train. */
function routeBoundary(route) {
    if (route.nextStopIndex !== null && route.nextStopIndex >= 0) return route.nextStopIndex;
    return Math.max(0, route.reachedCount || 0);
}

function drawRoute(route, train) {
    clearRoute();
    const stops = route.stops || [];
    if (stops.length < 2) { drawLeg(train); return; }

    const boundary = routeBoundary(route);
    const color = markerColor(train);
    const here = [train.latitude, train.longitude];

    const travelled = stops.slice(0, boundary).map(s => [s.latitude, s.longitude]);
    const remaining = stops.slice(boundary).map(s => [s.latitude, s.longitude]);
    // The train itself joins the two halves, so the line passes through its live position.
    if (travelled.length) travelled.push(here);
    if (remaining.length) remaining.unshift(here);

    if (travelled.length > 1) {
        L.polyline(travelled, { color: '#8fa6bd', weight: 4, opacity: .8 }).addTo(routeLayer);
    }
    if (remaining.length > 1) {
        L.polyline(remaining, { color: color, weight: 4, opacity: .95, dashArray: '8 7' }).addTo(routeLayer);
    }

    stops.forEach((stop, index) => {
        const reached = index < boundary;
        const isNext = index === boundary;
        const terminal = stop.kind === 'O' || stop.kind === 'D';
        L.circleMarker([stop.latitude, stop.longitude], {
            radius: terminal ? 6 : 4,
            color: '#0a121b',
            weight: 1.5,
            fillColor: isNext ? color : (reached ? '#8fa6bd' : '#e9eef4'),
            fillOpacity: 1
        }).bindTooltip(routeStopTooltip(stop), { direction: 'top', className: 'station-dot-label' })
          .addTo(routeLayer);
    });
}

function routeStopTooltip(stop) {
    const time = stop.actualArrival || stop.expectedArrival || stop.scheduledDeparture || stop.scheduledArrival;
    const late = lateText(stop.lateMinutes);
    return `<strong>${escapeHtml(stop.name || stop.code || '')}</strong>`
        + (time ? ` · ${escapeHtml(time)}` : '')
        + (late ? ` · ${escapeHtml(late)}` : '');
}

function lateText(lateMinutes) {
    if (lateMinutes === null || lateMinutes === undefined) return '';
    if (lateMinutes > 0) return `+${lateMinutes} min`;
    if (lateMinutes < 0) return `${Math.abs(lateMinutes)} min adiantado`;
    return 'no horário';
}

function lateColor(lateMinutes) {
    if (lateMinutes === null || lateMinutes === undefined) return 'var(--text-muted)';
    const category = CATEGORIES.find(c => lateMinutes >= c.minMinutes
        && (c.maxMinutes === -1 || lateMinutes <= c.maxMinutes));
    return lateMinutes <= 0 ? '#3ecf73' : (category ? category.textColor : 'var(--text-muted)');
}

function renderRouteList(route, train) {
    const stops = route.stops || [];
    const boundary = routeBoundary(route);

    const header = `
        <div class="history-stats">
            <div class="history-stat">
                <div class="history-stat-value">${stops.length}</div>
                <div class="history-stat-label">Paradas</div>
            </div>
            <div class="history-stat">
                <div class="history-stat-value" style="color:#3ecf73">${boundary}</div>
                <div class="history-stat-label">Percorridas</div>
            </div>
            <div class="history-stat">
                <div class="history-stat-value" style="color:${markerColor(train)}">${stops.length - boundary}</div>
                <div class="history-stat-label">Restantes</div>
            </div>
        </div>`;

    const rows = stops.map((stop, index) => {
        const reached = index < boundary;
        const isNext = index === boundary;
        const cls = isNext ? 'is-next' : (reached ? 'is-reached' : 'is-pending');
        const time = stop.actualArrival || stop.expectedArrival
            || stop.expectedDeparture || stop.scheduledArrival || stop.scheduledDeparture || '--:--';
        const kind = stop.kind === 'O' ? 'origem' : (stop.kind === 'D' ? 'destino' : '');
        const late = lateText(stop.lateMinutes);
        return `
            <div class="route-row ${cls}">
                <span class="route-dot"></span>
                <span class="route-name">${escapeHtml(stop.name || stop.code || '')}${kind ? `<span class="route-kind">${kind}</span>` : ''}</span>
                <span class="route-time">${escapeHtml(time)}${late ? ` <span class="route-late" style="color:${lateColor(stop.lateMinutes)}">${escapeHtml(late)}</span>` : ''}</span>
            </div>`;
    }).join('');

    document.getElementById('route-body').innerHTML = header + rows;
}

/** Draws the remaining leg to the next stop, so the arrow's meaning is visible. */
function drawLeg(train) {
    if (legLine) { map.removeLayer(legLine); legLine = null; }
    if (train.targetLat === null || train.targetLat === undefined) return;

    // Built as separate variables on purpose: a literal double square bracket anywhere in a
    // template — script blocks included — is read by Thymeleaf as an inlined expression and
    // aborts the render mid-response.
    const here = [train.latitude, train.longitude];
    const target = [train.targetLat, train.targetLon];
    legLine = L.polyline(
        [here, target],
        { color: markerColor(train), weight: 3, opacity: .9, dashArray: '5 6' }
    ).addTo(map);
}

/* ── history drawer ─────────────────────────────────────────────────────── */

async function loadHistory(trainCode) {
    if (!trainCode) return;
    const panel = document.getElementById('history-panel');
    const body = document.getElementById('history-body');
    panel.classList.add('visible');
    body.innerHTML = '<div class="empty-state" style="padding:1.2rem"><i class="bi bi-arrow-clockwise spin"></i> Loading…</div>';

    try {
        const data = await fetchJson(`/api/trains/${encodeURIComponent(trainCode)}/history?limit=20`, 8000);
        if (!data.snapshots) {
            body.innerHTML = `<div class="empty-state" style="padding:1.2rem">
                No delay history recorded for this service yet.</div>`;
            return;
        }
        const rows = (data.recent || []).map(entry => `
            <div class="history-row">
                <span class="history-station">${escapeHtml(entry.stationName || entry.stationCode || '—')}</span>
                <span class="history-late" style="color:${escapeHtml(entry.delayColor || '')}">${entry.lateMinutes >= 0 ? '+' : ''}${entry.lateMinutes} min</span>
                <span class="history-when">${escapeHtml(entry.trainDate || '')}${entry.schDepart ? ' · sch ' + escapeHtml(entry.schDepart) : ''}${entry.capturedAt ? ' · seen ' + escapeHtml(entry.capturedAt) : ''}</span>
            </div>`).join('');

        body.innerHTML = `
            <div class="history-stats">
                <div class="history-stat">
                    <div class="history-stat-value">${data.daysTracked}</div>
                    <div class="history-stat-label">Days tracked</div>
                </div>
                <div class="history-stat">
                    <div class="history-stat-value" style="color:var(--amber)">${data.avgDelay} min</div>
                    <div class="history-stat-label">Avg delay</div>
                </div>
                <div class="history-stat">
                    <div class="history-stat-value" style="color:#f87171">${data.delayedRate}%</div>
                    <div class="history-stat-label">Delayed</div>
                </div>
            </div>
            ${rows}`;
    } catch (error) {
        body.innerHTML = '<div class="empty-state" style="padding:1.2rem">Could not load history.</div>';
    }
}

/* ── station layer ──────────────────────────────────────────────────────── */

async function toggleStations(active) {
    if (!active) { map.removeLayer(stationLayer); return; }

    if (!stationsLoaded) {
        try {
            const stations = await fetchJson('/api/stations/all', 8000);
            stations.forEach(station => {
                // Darker than the text-muted slate it used to borrow, so it holds up on a light basemap.
                L.circleMarker([station.latitude, station.longitude], {
                    radius: 3.5, color: '#16283a', weight: 1.5, fillColor: '#5b7fa6', fillOpacity: .9
                })
                .bindTooltip(station.name, { direction: 'top', className: 'station-dot-label' })
                .addTo(stationLayer);
            });
            stationsLoaded = true;
        } catch (error) {
            console.error('Stations layer failed:', error);
            document.getElementById('chip-stations').classList.remove('active');
            return;
        }
    }
    stationLayer.addTo(map);
}

/* ── physical rail network ──────────────────────────────────────────────── */

function toggleRails(active) {
    if (!RAIL_URL) return;
    if (!railLayer) {
        railLayer = L.tileLayer(RAIL_URL, {
            attribution: RAIL_ATTRIBUTION,
            maxZoom: 19,
            opacity: .85
        });
    }
    if (active) railLayer.addTo(map);
    else map.removeLayer(railLayer);
}

/* ── refresh cycle ──────────────────────────────────────────────────────── */

function renderAll() {
    const trains = visibleTrains();
    syncMarkers(trains);
    renderList(trains);
    document.getElementById('stat-shown').textContent = trains.length;
}

function updateMeta(data) {
    const capturedAt = data.capturedAt ? new Date(data.capturedAt) : null;
    const timeText = capturedAt ? clockText(capturedAt) : '--:--:--';
    const clock = document.getElementById('clock');
    if (clock) clock.textContent = timeText;

    document.getElementById('capture-meta').textContent = capturedAt
        ? `${data.count} trains located · captured ${timeText}`
        : 'No successful capture yet';

    document.getElementById('stat-running').textContent = data.runningCount ?? 0;
    document.getElementById('stat-delayed').textContent = data.delayedCount ?? 0;

    const stale = Boolean(data.stale);
    document.getElementById('map-error').classList.toggle('visible', stale);
    document.getElementById('map-error-text').textContent = 'Irish Rail unreachable — showing last known positions';
    const pill = document.getElementById('live-pill');
    if (pill) pill.classList.toggle('stale', stale);
}

async function refresh() {
    const loading = document.getElementById('map-loading');
    loading.classList.add('visible');
    try {
        const data = await fetchJson('/api/train-positions', 9000);
        latestTrains = Array.isArray(data.positions) ? data.positions : [];
        renderAll();
        updateMeta(data);
    } catch (error) {
        document.getElementById('map-error').classList.add('visible');
        document.getElementById('map-error-text').textContent = 'Could not refresh positions — retrying';
        throw error;
    } finally {
        loading.classList.remove('visible');
    }
}

/* ── legend ─────────────────────────────────────────────────────────────── */

function renderLegend() {
    document.getElementById('legend-rows').innerHTML = CATEGORIES.map(category => {
        const range = category.maxMinutes === -1
            ? `${category.minMinutes}+ min`
            : `${category.minMinutes}–${category.maxMinutes} min`;
        return `<div class="legend-row">
            <span class="legend-swatch" style="background:${category.textColor}"></span>
            <span>${escapeHtml(category.isOnTime ? 'On time' : range)}</span>
        </div>`;
    }).join('');
}

/* ── wiring ─────────────────────────────────────────────────────────────── */

document.getElementById('train-list').addEventListener('click', event => {
    const row = event.target.closest('.train-row[data-key]');
    if (row) selectTrain(row.dataset.key);
});

document.getElementById('status-chips').addEventListener('click', event => {
    const chip = event.target.closest('.chip');
    if (!chip) return;

    if (chip.dataset.status) {
        filters.status = chip.dataset.status;
        document.querySelectorAll('.chip[data-status]').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        renderAll();
        return;
    }

    const active = chip.classList.toggle('active');
    if (chip.dataset.toggle === 'delayed') {
        filters.delayedOnly = active;
        renderAll();
    } else if (chip.dataset.toggle === 'stations') {
        toggleStations(active);
    } else if (chip.dataset.toggle === 'rails') {
        toggleRails(active);
    }
});

document.getElementById('drawer-tabs').addEventListener('click', event => {
    const tab = event.target.closest('.drawer-tab[data-pane]');
    if (tab) showDrawerPane(tab.dataset.pane);
});

let searchDebounce = null;
document.getElementById('train-search').addEventListener('input', event => {
    filters.query = event.target.value;
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(renderAll, 150);
});

document.getElementById('history-close').addEventListener('click', () => {
    document.getElementById('history-panel').classList.remove('visible');
});

// Popup buttons are recreated on every content update, so delegate from the container.
map.getContainer().addEventListener('click', event => {
    const button = event.target.closest('.popup-action[data-history]');
    if (!button) return;
    showDrawerPane('history');
    loadHistory(button.dataset.history);
});

renderLegend();
startPolling(refresh, REFRESH_MS);
