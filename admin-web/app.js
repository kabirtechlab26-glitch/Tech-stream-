const ADMIN_SECTIONS = [
  'DASHBOARD',
  'USERS',
  'STREAMS',
  'VIDEOS',
  'PAYMENTS',
  'SUBSCRIPTIONS',
  'REVENUE',
  'WITHDRAWALS',
  'SERVERS',
  'REPORTS',
  'SETTINGS',
];

let activeSection = 'DASHBOARD';
let bearerToken = ''; // Held in memory only, never persisted to localStorage
const API_BASE = window.TECHLIVE_API_BASE || 'https://api.techlive.cloud/v1';

const navEl = document.getElementById('adminNav');
const titleEl = document.getElementById('sectionTitle');
const bodyEl = document.getElementById('panelBody');
const serverBadgeEl = document.getElementById('serverBadge');

function renderNav() {
  navEl.replaceChildren();
  for (const section of ADMIN_SECTIONS) {
    const btn = document.createElement('button');
    btn.className = `nav-btn ${section === activeSection ? 'active' : ''}`;
    btn.textContent = section;
    btn.addEventListener('click', () => {
      activeSection = section;
      renderNav();
      renderSection();
    });
    navEl.appendChild(btn);
  }
}

function renderCard(label, value, hint) {
  const card = document.createElement('div');
  card.className = 'card';
  const h3 = document.createElement('h3');
  h3.textContent = label;
  const val = document.createElement('div');
  val.className = 'value';
  val.textContent = value;
  const p = document.createElement('p');
  p.className = 'muted';
  p.textContent = hint;
  card.append(h3, val, p);
  return card;
}

async function renderSection() {
  titleEl.textContent = activeSection;
  bodyEl.replaceChildren();

  if (!bearerToken) {
    const gateCard = renderCard(
      'Administrator Authentication Required',
      'Protected Console',
      'Sign in with a verified ADMIN or SUPER_ADMIN Firebase ID token to inspect live metrics, verify bKash/Nagad/Card payments, manage users, monitor FFmpeg cloud workers, and adjust SaaS limits.'
    );
    bodyEl.appendChild(gateCard);
    return;
  }

  try {
    const res = await fetch(`${API_BASE}/admin/servers/health`, {
      headers: { Authorization: `Bearer ${bearerToken}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    serverBadgeEl.textContent = `Server: ${data.serverStatus}`;
    serverBadgeEl.className = `badge ${String(data.serverStatus).toLowerCase()}`;

    const grid = document.createElement('div');
    grid.className = 'kpi-grid';
    grid.append(
      renderCard('Server Health', data.serverStatus, 'Live node telemetry'),
      renderCard('CPU Utilization', `${data.cpuPct}%`, 'FFmpeg cloud encoding load'),
      renderCard('RAM Utilization', `${data.ramPct}%`, 'Host memory utilization'),
      renderCard('Active Workers', String(data.activeWorkers), '7-day continuous cloud streams')
    );
    bodyEl.appendChild(grid);
  } catch (err) {
    bodyEl.appendChild(
      renderCard(
        `${activeSection} API Connection`,
        'Backend Offline / Unreachable',
        `Connect to ${API_BASE} with a valid Admin Bearer token to manage ${activeSection.toLowerCase()}.`
      )
    );
  }
}

document.getElementById('connectTokenBtn').addEventListener('click', () => {
  const token = window.prompt('Paste verified Admin Firebase ID Bearer token (held in memory only):');
  if (token && token.trim().length > 15) {
    bearerToken = token.trim();
    renderSection();
  }
});

renderNav();
renderSection();
