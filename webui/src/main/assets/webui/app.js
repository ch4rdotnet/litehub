// the browser editor. edits a copy of config.json and saves it back whole, the hub checks it
// before it's written, so a broken edit never reaches the screen
const $ = (id) => document.getElementById(id);
let config = null, schemas = [], sources = { calendars: [], feeds: [] }, entities = [], dash = 0, page = 0, picked = -1;

async function api(path, options = {}) {
  const r = await fetch(path, options);
  if (r.status === 401) { showLogin(); throw new Error("pin needed"); }
  const text = await r.text();
  if (!r.ok) throw new Error(text);
  return text;
}

function say(id, text, error) { const el = $(id); el.textContent = text; el.className = error ? "error" : "muted"; }

function showLogin() { $("login").hidden = false; $("app").hidden = true; $("pin").focus(); }

$("login-form").onsubmit = async (e) => {
  e.preventDefault();
  const r = await fetch("/api/login", { method: "POST", body: $("pin").value });
  if (r.ok) { $("login").hidden = true; start(); } else say("login-error", await r.text(), true);
};

document.querySelectorAll("nav button").forEach((b) => b.onclick = () => {
  document.querySelectorAll("nav button").forEach((x) => x.classList.toggle("on", x === b));
  document.querySelectorAll(".tab").forEach((t) => t.hidden = t.id !== "tab-" + b.dataset.tab);
  if (b.dataset.tab === "json") $("json").value = JSON.stringify(config, null, 2);
});

async function start() {
  try {
    schemas = JSON.parse(await api("/api/schema"));
    config = JSON.parse(await api("/api/config"));
  } catch (e) { return; }
  $("app").hidden = false;
  dash = Math.max(0, config.dashboards.findIndex((d) => d.id === config.activeDashboard));
  $("add-type").innerHTML = schemas.map((s) => `<option value="${s.type}">${s.name}</option>`).join("");
  loadConnections();
  api("/api/entities").then((t) => { entities = JSON.parse(t); drawSettings(); }).catch(() => {});
  render();
  refreshPreview();
  setInterval(refreshPreview, 30000);
}

function refreshPreview() { $("preview").src = "/api/preview.png?t=" + Date.now(); }

const board = () => config.dashboards[dash];
const current = () => board().pages[page];

function render() {
  $("dashboard").innerHTML = config.dashboards.map((d, i) =>
    `<option value="${i}" ${i === dash ? "selected" : ""}>${d.name}${d.id === config.activeDashboard ? " (on the hub)" : ""}</option>`).join("");
  $("dash-delete").disabled = config.dashboards.length < 2;
  $("dash-active").disabled = board().id === config.activeDashboard;
  page = Math.min(page, board().pages.length - 1);
  $("page-label").textContent = `page ${page + 1} of ${board().pages.length}`;
  $("page-prev").disabled = page === 0;
  $("page-next").disabled = page === board().pages.length - 1;
  $("page-remove").disabled = board().pages.length < 2;
  drawGrid();
  drawSettings();
}

function drawGrid() {
  const p = current(), grid = $("grid");
  grid.style.gridTemplateColumns = `repeat(${p.columns}, 1fr)`;
  grid.style.gridTemplateRows = `repeat(${p.rows}, 1fr)`;
  grid.innerHTML = "";
  for (let y = 0; y < p.rows; y++) for (let x = 0; x < p.columns; x++) {
    const c = document.createElement("div");
    c.className = "cell";
    c.style.gridArea = `${y + 1} / ${x + 1}`;
    grid.appendChild(c);
  }
  p.widgets.forEach((w, i) => {
    const box = document.createElement("div");
    box.className = "box" + (i === picked ? " picked" : "");
    place(box, w);
    const schema = schemas.find((s) => s.type === w.type);
    const detail = w.config.title || w.config.name || w.config.entity || "";
    box.innerHTML = `${schema ? schema.name : w.type}<small>${detail}</small><div class="handle"></div>`;
    box.onpointerdown = (e) => drag(e, i, e.target.classList.contains("handle"));
    grid.appendChild(box);
  });
}

function place(el, w) { el.style.gridArea = `${w.y + 1} / ${w.x + 1} / span ${w.h} / span ${w.w}`; }

function fits(p, w, ignore) {
  if (w.x < 0 || w.y < 0 || w.w < 1 || w.h < 1 || w.x + w.w > p.columns || w.y + w.h > p.rows) return false;
  return !p.widgets.some((o, i) => i !== ignore && w.x < o.x + o.w && o.x < w.x + w.w && w.y < o.y + o.h && o.y < w.y + w.h);
}

// drag a box to move it, its corner to resize, a tap picks it for the settings form
function drag(e, i, resizing) {
  e.preventDefault();
  const p = current(), start = { ...p.widgets[i] }, grid = $("grid").getBoundingClientRect();
  const cellW = grid.width / p.columns, cellH = grid.height / p.rows;
  const box = e.currentTarget, x0 = e.clientX, y0 = e.clientY;
  let moved = false, next = start;
  box.setPointerCapture(e.pointerId);
  box.onpointermove = (m) => {
    const dx = Math.round((m.clientX - x0) / cellW), dy = Math.round((m.clientY - y0) / cellH);
    if (dx || dy) moved = true;
    next = resizing ? { ...start, w: Math.max(1, start.w + dx), h: Math.max(1, start.h + dy) } : { ...start, x: start.x + dx, y: start.y + dy };
    place(box, next);
    box.classList.toggle("bad", !fits(p, next, i));
  };
  box.onpointerup = () => {
    box.onpointermove = box.onpointerup = null;
    if (moved && fits(p, next, i)) p.widgets[i] = next;
    if (!moved) picked = i;
    render();
  };
}

function drawSettings() {
  const form = $("settings"), w = current().widgets[picked];
  if (!w) { form.innerHTML = `<p class="muted">pick a widget to change it</p>`; return; }
  const schema = schemas.find((s) => s.type === w.type) || { name: w.type, fields: [] };
  form.innerHTML = `<h2>${schema.name}</h2>` + schema.fields.map((f) => field(f, w.config)).join("") +
    `<div class="bar"><button type="button" id="remove-widget">remove widget</button></div>`;
  form.oninput = form.onchange = () => {
    const out = {};
    for (const f of schema.fields) {
      if (f.kind === "calendars" || f.kind === "feeds") {
        const ids = [...form.querySelectorAll(`[name="${f.key}"]:checked`)].map((c) => c.value);
        if (ids.length) out[f.key] = ids;
      } else {
        const v = form.querySelector(`[name="${f.key}"]`).value.trim();
        if (v) out[f.key] = f.kind === "number" ? Number(v) : v;
      }
    }
    w.config = out;
    drawGrid();
  };
  $("remove-widget").onclick = () => { current().widgets.splice(picked, 1); picked = -1; render(); };
}

function field(f, values) {
  const v = values[f.key] ?? f.default ?? "";
  if (f.kind === "calendars" || f.kind === "feeds") {
    const list = (f.kind === "calendars" ? sources.calendars : sources.feeds) || [];
    if (!list.length) return `<label>${f.label}</label><p class="muted">none set up in sources.json</p>`;
    const chosen = values[f.key] || [];
    return `<label>${f.label}</label><div class="checks">` + list.map((s) =>
      `<label><input type="checkbox" name="${f.key}" value="${s.id}" ${chosen.includes(s.id) ? "checked" : ""}>${s.name}</label>`).join("") + `</div>`;
  }
  const type = f.kind === "number" ? "number" : "text";
  const value = String(v).replace(/"/g, "&quot;");
  const label = f.label + (f.required ? " (needed)" : "");
  if (f.kind !== "entity") return `<label>${label}<input name="${f.key}" type="${type}" value="${value}"></label>`;
  // suggestions only from the domains the field can use, the same filter as the hub's own picker
  const fit = entities.filter((e) => !f.domains || !f.domains.length || f.domains.includes(e.id.split(".")[0]));
  const options = fit.map((e) => `<option value="${e.id}">${e.name}${e.area ? " · " + e.area : ""}</option>`).join("");
  return `<label>${label}<input name="${f.key}" list="entities-${f.key}" value="${value}"><datalist id="entities-${f.key}">${options}</datalist></label>`;
}

function freeSpot(p, w, h) {
  for (const [ww, hh] of [[w, h], [1, 1]]) for (let y = 0; y < p.rows; y++) for (let x = 0; x < p.columns; x++)
    if (fits(p, { x, y, w: ww, h: hh }, -1)) return { x, y, w: ww, h: hh };
  return null;
}

$("add").onclick = () => {
  const schema = schemas.find((s) => s.type === $("add-type").value), p = current();
  const spot = freeSpot(p, schema.w, schema.h);
  if (!spot) return say("message", "no room on this page, move or shrink something first", true);
  p.widgets.push({ ...spot, type: schema.type, config: {} });
  picked = p.widgets.length - 1;
  render();
};

const uniqueId = (taken, base) => { let n = 1, id = base; while (taken.includes(id)) id = base + ++n; return id; };

$("dashboard").onchange = (e) => { dash = Number(e.target.value); page = 0; picked = -1; render(); };
$("dash-new").onclick = () => {
  const name = prompt("name for the new dashboard");
  if (!name) return;
  const like = current();
  config.dashboards.push({ id: uniqueId(config.dashboards.map((d) => d.id), name.toLowerCase().replace(/[^a-z0-9]+/g, "-")), name,
    theme: board().theme, pages: [{ id: "main", columns: like.columns, rows: like.rows, widgets: [] }] });
  dash = config.dashboards.length - 1; page = 0; picked = -1; render();
};
$("dash-copy").onclick = () => {
  const copy = JSON.parse(JSON.stringify(board()));
  copy.id = uniqueId(config.dashboards.map((d) => d.id), copy.id + "-copy");
  copy.name = copy.name + " copy";
  config.dashboards.push(copy);
  dash = config.dashboards.length - 1; render();
};
$("dash-delete").onclick = () => {
  if (!confirm(`delete ${board().name}?`)) return;
  const gone = config.dashboards.splice(dash, 1)[0];
  if (gone.id === config.activeDashboard) config.activeDashboard = config.dashboards[0].id;
  dash = 0; page = 0; picked = -1; render();
};
$("dash-active").onclick = () => { config.activeDashboard = board().id; render(); };
$("page-prev").onclick = () => { page--; picked = -1; render(); };
$("page-next").onclick = () => { page++; picked = -1; render(); };
$("page-add").onclick = () => {
  const like = current(), pages = board().pages;
  pages.push({ id: uniqueId(pages.map((p) => p.id), "page" + (pages.length + 1)), columns: like.columns, rows: like.rows, widgets: [] });
  page = pages.length - 1; picked = -1; render();
};
$("page-remove").onclick = () => { board().pages.splice(page, 1); picked = -1; render(); };

async function save(text, messageId) {
  try {
    await api("/api/config", { method: "PUT", body: text });
    config = JSON.parse(text);
    say(messageId, "saved, the hub has it", false);
    render();
    setTimeout(refreshPreview, 1500);
  } catch (e) { say(messageId, e.message, true); }
}

$("save").onclick = () => save(JSON.stringify(config, null, 2), "message");
$("json-apply").onclick = () => save($("json").value, "json-message");
$("export").onclick = () => {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([JSON.stringify(config, null, 2)], { type: "application/json" }));
  a.download = "litehub-config.json";
  a.click();
};
$("import").onchange = async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  $("json").value = await file.text();
  save($("json").value, "json-message");
};

async function loadConnections() {
  try {
    const ha = JSON.parse(await api("/api/ha"));
    $("ha-url").value = ha.url || "";
    $("ha-token").placeholder = ha.tokenSet ? "set, leave empty to keep it" : "not set";
    $("screensaver").value = await api("/api/screensaver");
    const text = await api("/api/sources");
    $("sources").value = text;
    sources = JSON.parse(text);
  } catch (e) { say("ha-message", e.message, true); }
}

$("ha-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    await api("/api/ha", { method: "PUT", body: JSON.stringify({ url: $("ha-url").value, token: $("ha-token").value }) });
    $("ha-token").value = "";
    say("ha-message", "saved, the hub reconnects with it", false);
    loadConnections();
  } catch (e) { say("ha-message", e.message, true); }
};
$("screensaver-save").onclick = async () => {
  try {
    await api("/api/screensaver", { method: "PUT", body: $("screensaver").value });
    say("screensaver-message", "saved, the hub uses it straight away", false);
  } catch (e) { say("screensaver-message", e.message, true); }
};
$("sources-save").onclick = async () => {
  try {
    await api("/api/sources", { method: "PUT", body: $("sources").value });
    sources = JSON.parse($("sources").value);
    say("sources-message", "saved, the hub reloads its calendars and feeds", false);
  } catch (e) { say("sources-message", e.message, true); }
};

start();
