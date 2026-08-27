/* escapeHtml, clockText and fetchJson come from /js/app.js */

function selectedText(id) {
    const select = document.getElementById(id);
    return select.options[select.selectedIndex]?.text || '';
}

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

function renderOption(option) {
    const depart = option.expDepart || option.schDepart || '--';
    const arrive = option.expArrival || option.schArrival || '--';
    const last = option.lastLocation ? escapeHtml(option.lastLocation) : '<span class="muted">--</span>';
    return `<tr>
        <td><span class="train-chip">${escapeHtml(option.trainCode || '--')}</span></td>
        <td class="muted">${escapeHtml(option.trainType || '--')}</td>
        <td>${escapeHtml(option.destination || '--')}</td>
        <td>${escapeHtml(option.direction || '--')}</td>
        <td>${escapeHtml(depart)}</td>
        <td>${escapeHtml(arrive)}</td>
        <td>${dueText(Number(option.dueIn || 0))}</td>
        <td><span class="badge-status">${escapeHtml(option.status || '--')}</span></td>
        <td>${delayBadge(Number(option.late || 0))}</td>
        <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${last}</td>
    </tr>`;
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

document.getElementById('from-station').addEventListener('change', fetchJourneyOptions);
document.getElementById('to-station').addEventListener('change', fetchJourneyOptions);
fetchJourneyOptions();
