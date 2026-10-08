/* escapeHtml, clockText and fetchJson come from /js/app.js; STATIONS is inlined by the template. */

/* ── searchable station picker ─────────────────────────────────────────────
   A hundred stations is too many to scroll through, so each field is a text input
   that filters the list as you type. The chosen station's code lives in a hidden
   input with the original id (from-station / to-station), so the rest of the page
   keeps reading `.value` and listening for `change` exactly as it did with <select>. */

function foldName(value) {
    return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
}

function highlight(name, query) {
    if (!query) return escapeHtml(name);
    const at = foldName(name).indexOf(query);
    if (at < 0) return escapeHtml(name);
    return escapeHtml(name.slice(0, at)) + '<mark>' + escapeHtml(name.slice(at, at + query.length)) + '</mark>'
        + escapeHtml(name.slice(at + query.length));
}

function nameOf(code) {
    return STATIONS.find(s => s.code === code)?.name || '';
}

function stationPicker(prefix, initial) {
    const input  = document.getElementById(prefix + '-station-input');
    const hidden = document.getElementById(prefix + '-station');
    const list   = document.getElementById(prefix + '-station-list');
    let matches = [];
    let active = -1;

    function select(station) {
        if (!station) return;
        const changed = hidden.value !== station.code;
        hidden.value = station.code;
        input.value = station.name;
        close();
        if (changed) hidden.dispatchEvent(new Event('change'));
    }

    function close() {
        list.hidden = true;
        input.setAttribute('aria-expanded', 'false');
        active = -1;
    }

    function render(query) {
        const q = foldName(query);
        // Name starts with the query, then any word does ("conn" → Dublin Connolly before
        // Castleconnell), then anywhere in the name; each tier keeps STATIONS' alphabetical order.
        const starts = [], words = [], contains = [];
        for (const s of STATIONS) {
            const folded = foldName(s.name);
            if (!q || folded.startsWith(q)) starts.push(s);
            else if (folded.includes(' ' + q)) words.push(s);
            else if (folded.includes(q)) contains.push(s);
        }
        matches = starts.concat(words, contains);
        active = matches.length ? 0 : -1;
        list.innerHTML = matches.length
            ? matches.map((s, i) => `<li role="option" data-index="${i}" class="${i === active ? 'active' : ''}"`
                + ` aria-selected="${i === active}">${highlight(s.name, q)}</li>`).join('')
            : '<li class="no-match">No station matches</li>';
        list.hidden = false;
        input.setAttribute('aria-expanded', 'true');
    }

    function moveActive(delta) {
        if (!matches.length) return;
        active = (active + delta + matches.length) % matches.length;
        list.querySelectorAll('li[data-index]').forEach((li, i) => {
            li.classList.toggle('active', i === active);
            li.setAttribute('aria-selected', String(i === active));
        });
        list.children[active]?.scrollIntoView({ block: 'nearest' });
    }

    input.addEventListener('focus', () => { input.select(); render(''); });
    input.addEventListener('input', () => render(input.value));
    input.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            if (list.hidden) render(input.value); else moveActive(1);
        } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            moveActive(-1);
        } else if (event.key === 'Enter') {
            event.preventDefault();
            if (!list.hidden && active >= 0) select(matches[active]);
        } else if (event.key === 'Escape') {
            close();
            input.value = nameOf(hidden.value);
        }
    });
    // mousedown rather than click: it fires before the input's blur closes the list.
    list.addEventListener('mousedown', event => {
        const li = event.target.closest('li[data-index]');
        if (li) { event.preventDefault(); select(matches[Number(li.dataset.index)]); }
    });
    input.addEventListener('blur', () => {
        // A typed name that exactly matches one station counts as choosing it; otherwise revert.
        const exact = STATIONS.find(s => foldName(s.name) === foldName(input.value));
        if (exact) select(exact);
        else { close(); input.value = nameOf(hidden.value); }
    });

    if (initial) select(initial);
}

function selectedText(id) {
    return nameOf(document.getElementById(id).value);
}

/* ── results table ─────────────────────────────────────────────────────── */

function dueText(dueIn) {
    if (dueIn === 0) return '<span class="due-now">Now</span>';
    if (dueIn <= 5) return `<span class="due-soon">${dueIn} min</span>`;
    return `${dueIn} min`;
}

function delayBadge(late) {
    return late >= 5
        ? `<span class="delay-late">+${late} min</span>`
        : '<span class="delay-ok">On time</span>';
}

function minutesBetween(start, end) {
    const parse = value => {
        const match = String(value || '').match(/^(\d{1,2}):(\d{2})/);
        return match ? Number(match[1]) * 60 + Number(match[2]) : null;
    };
    const a = parse(start), b = parse(end);
    if (a === null || b === null) return null;
    return (b - a + 1440) % 1440;
}

function renderOption(option, index) {
    const depart = option.expDepart || option.schDepart || '--';
    const arrive = option.expArrival || option.schArrival || '--';
    const last = option.lastLocation ? escapeHtml(option.lastLocation) : '<span class="muted">--</span>';
    const duration = minutesBetween(depart, arrive);
    return `<tr data-train="${escapeHtml(option.trainCode || '')}" data-index="${index}" tabindex="0" aria-label="Select ${escapeHtml(option.trainCode || 'train')}">
        <td><span class="train-chip">${escapeHtml(option.trainCode || '--')}</span></td>
        <td class="muted">${escapeHtml(option.trainType || '--')}</td>
        <td>${escapeHtml(option.destination || '--')}</td>
        <td>${escapeHtml(option.direction || '--')}</td>
        <td>${escapeHtml(depart)}</td>
        <td>${escapeHtml(arrive)}${duration !== null ? `<div class="muted" style="font-size:.7rem">${duration} min</div>` : ''}</td>
        <td>${dueText(Number(option.dueIn || 0))}</td>
        <td><span class="badge-status">${escapeHtml(option.status || '--')}</span></td>
        <td>${delayBadge(Number(option.late || 0))}</td>
        <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${last}</td>
    </tr>`;
}

function renderFastest(options) {
    const ranked = options.map((option, index) => ({ option, index, duration: minutesBetween(option.expDepart || option.schDepart, option.expArrival || option.schArrival) }))
        .filter(item => item.duration !== null).sort((a, b) => a.duration - b.duration);
    const value = document.getElementById('fastest-value');
    const meta = document.getElementById('fastest-meta');
    if (!ranked.length) { value.textContent = 'Not available'; meta.textContent = 'No complete arrival and departure times.'; return; }
    const fastest = ranked[0];
    value.textContent = `${fastest.duration} min`;
    meta.textContent = `${fastest.option.trainCode || 'Train'} · ${fastest.option.expDepart || fastest.option.schDepart || '--'} to ${fastest.option.expArrival || fastest.option.schArrival || '--'}`;
}

async function selectJourney(option, row) {
    document.querySelectorAll('#journey-tbody tr').forEach(item => item.classList.remove('selected'));
    row?.classList.add('selected');
    const detail = document.getElementById('journey-detail');
    detail.hidden = false;
    document.getElementById('detail-train').textContent = `${option.trainType || 'Service'} ${option.trainCode || ''}`.trim();
    document.getElementById('detail-route').textContent = `${selectedText('from-station')} to ${selectedText('to-station')} · ${option.expDepart || option.schDepart || '--'} to ${option.expArrival || option.schArrival || '--'}`;
    document.getElementById('detail-status').textContent = option.status || (Number(option.late || 0) >= 5 ? 'Delayed' : 'On time');
    const timeline = document.getElementById('journey-timeline');
    timeline.innerHTML = '<div class="empty-state" style="padding:1rem"><i class="bi bi-arrow-clockwise spin me-2"></i>Loading live stops</div>';
    try {
        const route = await fetchJson(`/api/trains/${encodeURIComponent((option.trainCode || '').trim())}/route`, 12000);
        const stops = Array.isArray(route.stops) ? route.stops : [];
        timeline.innerHTML = stops.length ? stops.map(stop => `<div class="timeline-stop ${stop.reached ? 'reached' : ''} ${stop.next ? 'next' : ''}">
            <span class="timeline-dot" aria-hidden="true"></span><div class="timeline-name">${escapeHtml(stop.name || stop.code || 'Stop')}</div>
            <div class="timeline-time">${escapeHtml(stop.expectedDeparture || stop.expectedArrival || stop.scheduledDeparture || stop.scheduledArrival || '--')}${stop.next ? ' · Next' : stop.reached ? ' · Reached' : ''}</div>
        </div>`).join('') : '<div class="empty-state" style="padding:1rem">No published stop sequence is available for this service.</div>';
    } catch (error) {
        timeline.innerHTML = '<div class="empty-state" style="padding:1rem">Unable to load the live stop sequence.</div>';
    }
}

function renderEmpty(message) {
    document.getElementById('journey-tbody').innerHTML = `<tr><td colspan="10">
        <div class="empty-state"><i class="bi bi-info-circle fs-1 d-block mb-2"></i>${escapeHtml(message)}</div>
    </td></tr>`;
}

async function fetchJourneyOptions() {
    const fromCode = document.getElementById('from-station').value;
    const toCode = document.getElementById('to-station').value;
    const btn = document.getElementById('search-btn');

    if (!fromCode || !toCode || fromCode === toCode) {
        renderEmpty('Choose two different stations.');
        document.getElementById('result-count').textContent = '0 trains';
        return;
    }

    btn.disabled = true;
    btn.innerHTML = '<i class="bi bi-arrow-clockwise spin"></i><span>Loading</span>';
    document.getElementById('route-summary').textContent = `${selectedText('from-station')} to ${selectedText('to-station')}`;

    try {
        const params = new URLSearchParams({ fromStationCode: fromCode, toStationCode: toCode });
        const data = await fetchJson(`/api/journey-options?${params}`, 10000);
        const options = Array.isArray(data.options) ? data.options : [];
        document.getElementById('clock').textContent = data.updatedAt || clockText();
        document.getElementById('result-count').textContent = `${options.length} train${options.length === 1 ? '' : 's'}`;
        window.currentJourneyOptions = options;
        renderFastest(options);
        document.getElementById('journey-tbody').innerHTML = options.length
            ? options.map(renderOption).join('')
            : `<tr><td colspan="10"><div class="empty-state"><i class="bi bi-train-front fs-1 d-block mb-2"></i>No matching live services found for this route.</div></td></tr>`;
    } catch (error) {
        console.error('Journey search failed:', error);
        renderEmpty('Unable to load journey options right now.');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="bi bi-search"></i><span>Find trains</span>';
    }
}

stationPicker('from', STATIONS[0]);
stationPicker('to', STATIONS[1]);
document.getElementById('from-station').addEventListener('change', fetchJourneyOptions);
document.getElementById('to-station').addEventListener('change', fetchJourneyOptions);
document.getElementById('swap-stations').addEventListener('click', () => {
    const from = document.getElementById('from-station');
    const to = document.getElementById('to-station');
    const value = from.value; from.value = to.value; to.value = value;
    document.getElementById('from-station-input').value = nameOf(from.value);
    document.getElementById('to-station-input').value = nameOf(to.value);
    fetchJourneyOptions();
});
document.getElementById('journey-tbody').addEventListener('click', event => {
    const row = event.target.closest('tr[data-index]');
    if (row) selectJourney(window.currentJourneyOptions?.[Number(row.dataset.index)], row);
});
document.getElementById('journey-tbody').addEventListener('keydown', event => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    const row = event.target.closest('tr[data-index]');
    if (row) { event.preventDefault(); selectJourney(window.currentJourneyOptions?.[Number(row.dataset.index)], row); }
});
fetchJourneyOptions();
