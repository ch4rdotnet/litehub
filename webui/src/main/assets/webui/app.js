// the browser editor. edits a copy of config.json and saves it back whole, the hub checks it
// before it's written, so a broken edit never reaches the screen
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
let config = null, schemas = [], sources = { calendars: [], feeds: [] }, entities = [], dash = 0, page = 0, picked = -1;
// whole config snapshots for undo, and what the hub last saved so unsaved changes show
let undoStack = [], redoStack = [], savedText = "";
// a pointer has to move this far before a press on a tile counts as a drag (css px)
const DRAG_SLOP = 4;

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

document.querySelectorAll("header nav button").forEach((b) => b.onclick = () => {
  document.querySelectorAll("header nav button").forEach((x) => x.classList.toggle("on", x === b));
  document.querySelectorAll(".tab").forEach((t) => t.hidden = t.id !== "tab-" + b.dataset.tab);
  if (b.dataset.tab === "json") $("json").value = JSON.stringify(config, null, 2);
  if (b.dataset.tab === "themes") drawThemes();
});

async function start() {
  try {
    schemas = JSON.parse(await api("/api/schema"));
    config = JSON.parse(await api("/api/config"));
  } catch (e) { return; }
  savedText = JSON.stringify(config);
  $("app").hidden = false;
  dash = Math.max(0, config.dashboards.findIndex((d) => d.id === config.activeDashboard));
  loadSettings();
  api("/api/themes").then((t) => { presets = JSON.parse(t); drawThemes(); }).catch(() => {});
  api("/api/sources").then((t) => { sources = JSON.parse(t); }).catch(() => {});
  api("/api/entities").then((t) => { entities = JSON.parse(t); render(); }).catch(() => {});
  render();
  refreshPreview();
  setInterval(refreshPreview, 30000);
}

function refreshPreview() { $("preview").src = "/api/preview.png?t=" + Date.now(); }

const board = () => config.dashboards[dash];
const current = () => board().pages[page];
const schemaOf = (type) => schemas.find((s) => s.type === type);
const entityName = (id) => entities.find((e) => e.id === id)?.name;

// call before changing config, so undo can go back to it
function remember() {
  undoStack.push(JSON.stringify(config));
  redoStack = [];
}

function undo() {
  if (!undoStack.length) return;
  redoStack.push(JSON.stringify(config));
  config = JSON.parse(undoStack.pop());
  picked = -1;
  render();
}

function redo() {
  if (!redoStack.length) return;
  undoStack.push(JSON.stringify(config));
  config = JSON.parse(redoStack.pop());
  picked = -1;
  render();
}

const dirty = () => JSON.stringify(config) !== savedText;
window.addEventListener("beforeunload", (e) => { if (config && dirty()) e.preventDefault(); });

function render() {
  $("dashboard").innerHTML = config.dashboards.map((d, i) =>
    `<option value="${i}" ${i === dash ? "selected" : ""}>${esc(d.name)}${d.id === config.activeDashboard ? " (on the hub)" : ""}</option>`).join("");
  $("dash-delete").disabled = config.dashboards.length < 2;
  $("dash-active").disabled = board().id === config.activeDashboard;
  page = Math.min(page, board().pages.length - 1);
  const p = current();
  $("page-label").textContent = `page ${page + 1} of ${board().pages.length}`;
  $("page-prev").disabled = page === 0;
  $("page-next").disabled = page === board().pages.length - 1;
  $("page-remove").disabled = board().pages.length < 2;
  $("grid-columns").value = p.columns;
  $("grid-rows").value = p.rows;
  $("grid-density").value = p.density || "comfortable";
  $("undo").disabled = !undoStack.length;
  $("redo").disabled = !redoStack.length;
  $("dirty").textContent = dirty() ? "unsaved changes" : "";
  drawGrid();
  drawSettings();
  if (!$("tab-themes").hidden) drawThemes();
}

// the layout geometry, all in grid cells
const overlaps = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const inside = (w, p) => w.x >= 0 && w.y >= 0 && w.w >= 1 && w.h >= 1 && w.x + w.w <= p.columns && w.y + w.h <= p.rows;

// the free spot nearest where w is now, avoiding the tiles listed in taken
function nearest(w, list, taken, p) {
  let best = null, bestDistance = Infinity;
  for (let y = 0; y + w.h <= p.rows; y++) for (let x = 0; x + w.w <= p.columns; x++) {
    const c = { x, y, w: w.w, h: w.h };
    if (taken.some((t) => overlaps(c, list[t]))) continue;
    const d = Math.abs(x - w.x) + Math.abs(y - w.y);
    if (d < bestDistance) { bestDistance = d; best = { x, y }; }
  }
  return best;
}

// tile i goes where it's put, anything it lands on moves to the nearest free space (biggest
// first, so they get the room). null when something can't fit anywhere
function arrange(list, i, next, p) {
  if (!inside(next, p)) return null;
  const out = list.map((w) => ({ ...w }));
  out[i] = { ...out[i], x: next.x, y: next.y, w: next.w, h: next.h };
  const bumped = out.map((_, j) => j).filter((j) => j !== i && overlaps(out[j], out[i]));
  const settled = [i, ...out.map((_, j) => j).filter((j) => j !== i && !bumped.includes(j))];
  bumped.sort((a, b) => out[b].w * out[b].h - out[a].w * out[a].h);
  for (const j of bumped) {
    const spot = nearest(out[j], out, settled, p);
    if (!spot) return null;
    out[j] = { ...out[j], ...spot };
    settled.push(j);
  }
  return out;
}

function freeSpot(p, w, h) {
  for (const [ww, hh] of [[w, h], [1, 1]]) {
    const spot = nearest({ x: 0, y: 0, w: ww, h: hh }, p.widgets, p.widgets.map((_, j) => j), p);
    if (spot && ww <= p.columns && hh <= p.rows) return { ...spot, w: ww, h: hh };
  }
  return null;
}

function put(el, w, p) {
  el.style.left = `${w.x / p.columns * 100}%`;
  el.style.top = `${w.y / p.rows * 100}%`;
  el.style.width = `${w.w / p.columns * 100}%`;
  el.style.height = `${w.h / p.rows * 100}%`;
}

// what a tile says about itself, its type, a name and a hint of what's in it
function tileHtml(w) {
  const schema = schemaOf(w.type);
  const c = w.config || {};
  const title = c.title || c.name || entityName(c.entity) || c.entity || "";
  let inner = "";
  if (w.type === "entities") {
    inner = `<div class="minis">${(c.entities || []).map((id) => `<span>${esc(entityName(id) || id)}</span>`).join("")}</div>`;
  } else if (c.entity && title !== c.entity) {
    inner = `<small>${esc(c.entity)}</small>`;
  }
  return `<div class="box"><span class="kind">${esc(schema ? schema.name : w.type)}</span>` +
    `<strong>${esc(title)}</strong>${inner}<span class="size">${w.w} by ${w.h}</span><div class="handle"></div></div>`;
}

function drawGrid() {
  const p = current(), grid = $("grid");
  grid.classList.toggle("compact", p.density === "compact");
  grid.style.setProperty("--columns", p.columns);
  grid.style.setProperty("--rows", p.rows);
  grid.innerHTML = `<div class="cells"></div><div id="ghost" class="ghost" hidden></div>`;
  grid.firstChild.innerHTML = "<i></i>".repeat(p.columns * p.rows);
  p.widgets.forEach((w, i) => {
    const slot = document.createElement("div");
    slot.className = "slot" + (i === picked ? " picked" : "");
    slot.dataset.i = i;
    put(slot, w, p);
    slot.innerHTML = tileHtml(w);
    slot.onpointerdown = (e) => drag(e, i, e.target.classList.contains("handle"));
    grid.appendChild(slot);
  });
}

// drag a tile to move it, its corner to resize it, a tap picks it for the settings form. the
// others show where they'd be pushed to while the drag goes on
function drag(e, i, resizing) {
  if (e.button !== 0) return;
  e.preventDefault();
  const p = current(), before = p.widgets.map((w) => ({ ...w })), start = before[i];
  const box = $("grid").getBoundingClientRect(), cellW = box.width / p.columns, cellH = box.height / p.rows;
  const slots = [...$("grid").querySelectorAll(".slot")], slot = slots[i], ghost = $("ghost");
  const x0 = e.clientX, y0 = e.clientY;
  let moved = false, result = null;
  slot.setPointerCapture(e.pointerId);
  slot.onpointermove = (m) => {
    const px = m.clientX - x0, py = m.clientY - y0;
    if (!moved && Math.abs(px) + Math.abs(py) < DRAG_SLOP) return;
    if (!moved) { moved = true; slot.classList.add("dragging"); ghost.hidden = false; }
    const dx = Math.round(px / cellW), dy = Math.round(py / cellH);
    const next = resizing ? { ...start, w: Math.max(1, start.w + dx), h: Math.max(1, start.h + dy) } : { ...start, x: start.x + dx, y: start.y + dy };
    result = arrange(before, i, next, p);
    put(ghost, { x: Math.max(0, Math.min(next.x, p.columns - 1)), y: Math.max(0, Math.min(next.y, p.rows - 1)), w: next.w, h: next.h }, p);
    ghost.classList.toggle("bad", !result);
    (result || before).forEach((w, j) => { if (j !== i) put(slots[j], w, p); });
    if (resizing) {
      slot.style.width = `${start.w * cellW + px}px`;
      slot.style.height = `${start.h * cellH + py}px`;
    } else {
      slot.style.transform = `translate(${px}px, ${py}px)`;
    }
  };
  slot.onpointerup = slot.onpointercancel = () => {
    slot.onpointermove = slot.onpointerup = slot.onpointercancel = null;
    if (moved && result) {
      remember();
      p.widgets = result;
    }
    if (moved && !result) say("message", "there's no room to move the others out of the way", true);
    if (!moved) picked = i;
    render();
  };
}

// arrow keys nudge the picked tile (shift grows and shrinks it), the same pushing as a drag
function nudge(dx, dy, resizing) {
  const p = current(), w = p.widgets[picked];
  if (!w) return;
  const next = resizing ? { ...w, w: w.w + dx, h: w.h + dy } : { ...w, x: w.x + dx, y: w.y + dy };
  const result = arrange(p.widgets, picked, next, p);
  if (!result) return;
  remember();
  p.widgets = result;
  render();
}

document.addEventListener("keydown", (e) => {
  if (!config || $("picker").open) return;
  const typing = /^(input|textarea|select)$/i.test(e.target.tagName);
  const mod = e.ctrlKey || e.metaKey;
  // undo works on the themes tab too, everything else here is the layout's
  if (!$("tab-themes").hidden && mod && e.key.toLowerCase() === "z") { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
  if ($("tab-layout").hidden) return;
  if (mod && e.key.toLowerCase() === "z") { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
  if (mod && e.key.toLowerCase() === "y") { e.preventDefault(); redo(); return; }
  if (typing) return;
  const arrows = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
  if (arrows[e.key] && picked >= 0) { e.preventDefault(); nudge(...arrows[e.key], e.shiftKey); }
  if ((e.key === "Delete" || e.key === "Backspace") && picked >= 0) { e.preventDefault(); removePicked(); }
  if (e.key === "Escape") { picked = -1; render(); }
});

function removePicked() {
  remember();
  current().widgets.splice(picked, 1);
  picked = -1;
  render();
}

// a page's grid. keep tile sizes scales every tile with the grid, so going from 4 by 3 to 8 by 6
// keeps the layout and just gives it finer steps. anything that ends up overlapping moves aside
function setGrid(columns, rows) {
  const p = current();
  if (!(columns >= 1 && rows >= 1)) return;
  const keep = $("grid-keep").checked;
  const fx = keep ? columns / p.columns : 1, fy = keep ? rows / p.rows : 1;
  const target = { columns, rows };
  const out = p.widgets.map((w) => {
    const ww = Math.min(columns, Math.max(1, Math.round(w.w * fx))), hh = Math.min(rows, Math.max(1, Math.round(w.h * fy)));
    return { ...w, w: ww, h: hh, x: Math.min(columns - ww, Math.round(w.x * fx)), y: Math.min(rows - hh, Math.round(w.y * fy)) };
  });
  const placed = [];
  for (let j = 0; j < out.length; j++) {
    if (placed.some((k) => overlaps(out[k], out[j]))) {
      const spot = nearest(out[j], out, placed, target);
      if (!spot) { say("message", "the tiles don't all fit on a grid that small", true); render(); return; }
      out[j] = { ...out[j], ...spot };
    }
    placed.push(j);
  }
  remember();
  p.columns = columns;
  p.rows = rows;
  p.widgets = out;
  say("message", "", false);
  render();
}

$("grid-columns").onchange = () => setGrid(Number($("grid-columns").value), current().rows);
$("grid-rows").onchange = () => setGrid(current().columns, Number($("grid-rows").value));
$("grid-density").onchange = () => {
  remember();
  // comfortable is the default, left out so older configs stay as they were
  if ($("grid-density").value === "compact") current().density = "compact"; else delete current().density;
  render();
};
$("undo").onclick = undo;
$("redo").onclick = redo;

// every widget type as a card, its shape, name and what it's for
function openPicker() {
  $("picker-search").value = "";
  say("picker-message", "", false);
  drawPicker();
  $("picker").showModal();
  $("picker-search").focus();
}

function drawPicker() {
  const q = $("picker-search").value.trim().toLowerCase();
  const list = schemas.filter((s) => !q || `${s.name} ${s.description || ""}`.toLowerCase().includes(q));
  $("picker-list").innerHTML = list.map((s) => `<button type="button" class="card" data-type="${s.type}">` +
    `<span class="shape" style="--w: ${s.w}; --h: ${s.h}">${"<i></i>".repeat(s.w * s.h)}</span>` +
    `<strong>${esc(s.name)}</strong><small>${esc(s.description || "")}</small><span class="muted">${s.w} by ${s.h}</span></button>`).join("") ||
    `<p class="muted">nothing matches</p>`;
  $("picker-list").querySelectorAll(".card").forEach((c) => c.onclick = () => addWidget(c.dataset.type));
}

function addWidget(type) {
  const schema = schemaOf(type), p = current();
  const spot = freeSpot(p, schema.w, schema.h);
  if (!spot) return say("picker-message", "no room on this page, move or shrink something first", true);
  remember();
  p.widgets.push({ ...spot, type, config: {} });
  picked = p.widgets.length - 1;
  $("picker").close();
  render();
}

$("add").onclick = openPicker;
$("picker-close").onclick = () => $("picker").close();
$("picker-search").oninput = drawPicker;

function drawSettings() {
  const form = $("settings"), w = current().widgets[picked];
  delete form.dataset.remembered;
  if (!w) { form.innerHTML = `<p class="muted">pick a widget to change it</p>`; return; }
  const schema = schemaOf(w.type) || { name: w.type, fields: [] };
  form.innerHTML = `<h2>${esc(schema.name)}</h2>` + schema.fields.map((f) => field(f, w.config)).join("") +
    `<div class="bar"><button type="button" id="copy-widget">duplicate</button><button type="button" id="remove-widget">remove widget</button></div>`;
  // one undo step per visit to the form, not one per key
  form.onfocusin = () => { if (!form.dataset.remembered) { remember(); form.dataset.remembered = "1"; } };
  form.oninput = form.onchange = () => {
    const out = {};
    for (const f of schema.fields) {
      const v = readField(form, f);
      if (v !== undefined && !(Array.isArray(v) && !v.length)) out[f.key] = v;
    }
    w.config = out;
    drawGrid();
    $("dirty").textContent = dirty() ? "unsaved changes" : "";
  };
  $("remove-widget").onclick = removePicked;
  $("copy-widget").onclick = () => {
    const p = current(), spot = freeSpot(p, w.w, w.h);
    if (!spot) return say("message", "no room on this page for a copy", true);
    remember();
    p.widgets.push({ ...structuredClone(w), ...spot });
    picked = p.widgets.length - 1;
    render();
  };
}

function field(f, values) {
  const v = values[f.key] ?? f.default ?? "";
  if (f.kind === "calendars" || f.kind === "feeds") {
    const list = (f.kind === "calendars" ? sources.calendars : sources.feeds) || [];
    if (!list.length) return `<label>${f.label}</label><p class="muted">none set up in sources.json</p>`;
    const chosen = values[f.key] || [];
    return `<label>${f.label}</label><div class="checks">` + list.map((s) =>
      `<label><input type="checkbox" name="${f.key}" value="${s.id}" ${chosen.includes(s.id) ? "checked" : ""}>${esc(s.name)}</label>`).join("") + `</div>`;
  }
  const value = esc(Array.isArray(v) ? v.join(", ") : v);
  const label = f.label + (f.required ? " (needed)" : "");
  if (f.kind === "toggle") return `<label class="toggle"><input type="checkbox" name="${f.key}" ${v === true ? "checked" : ""}>${label}</label>`;
  if (f.kind === "choice") return `<label>${label}<select name="${f.key}">` +
    f.options.map((o) => `<option value="${o.value}" ${o.value === v ? "selected" : ""}>${o.label}</option>`).join("") + `</select></label>`;
  if (f.kind === "number") return `<label>${label}<input name="${f.key}" type="number" step="any" min="${f.min ?? ""}" max="${f.max ?? ""}" value="${value}"></label>`;
  if (f.kind === "color") return `<label>${label}</label><div class="swatches"><button type="button" data-c="">auto</button>` +
    (hubSettings?.palette || []).map((c) => `<button type="button" class="swatch" data-c="${c}" style="background: ${c}"></button>`).join("") +
    `<input name="${f.key}" value="${value}" placeholder="#rrggbb"></div>`;
  if (f.kind === "time") return `<label>${label}<input name="${f.key}" type="time" value="${value}"></label>`;
  if (f.kind === "secret") return `<label>${label}<input name="${f.key}" type="password" autocomplete="off" placeholder="leave blank to keep it"></label>`;
  if (f.kind !== "entity" && f.kind !== "entities") return `<label>${label}<input name="${f.key}" value="${value}"></label>`;
  // suggestions only from the domains the field can use, the same filter as the hub's own picker
  const fit = entities.filter((e) => !f.domains || !f.domains.length || f.domains.includes(e.id.split(".")[0]));
  const options = fit.map((e) => `<option value="${e.id}">${esc(e.name)}${e.area ? " · " + esc(e.area) : ""}</option>`).join("");
  if (f.kind === "entity") return `<label>${label}<input name="${f.key}" list="entities-${f.key}" value="${value}"><datalist id="entities-${f.key}">${options}</datalist></label>`;
  // a chip per entity, the list itself rides in a hidden input so reading the form stays the same
  const list = Array.isArray(v) ? v : [];
  return `<label>${label}</label><div class="chips"><input type="hidden" name="${f.key}" value="${esc(JSON.stringify(list))}">` +
    `<span class="chip-list">${chips(list)}</span>` +
    `<input class="chip-add" list="entities-${f.key}" placeholder="add an entity"><datalist id="entities-${f.key}">${options}</datalist></div>`;
}

const chips = (list) => list.map((id) => `<span class="chip">${esc(entityName(id) || id)}<button type="button" data-remove="${esc(id)}">×</button></span>`).join("");

// chip lists in any form, picking from the suggestions adds one, the cross takes one off
function editChips(box, change) {
  const hidden = box.querySelector("input[type=hidden]");
  const list = change(JSON.parse(hidden.value));
  hidden.value = JSON.stringify(list);
  box.querySelector(".chip-list").innerHTML = chips(list);
  box.closest("form").dispatchEvent(new Event("input"));
}
document.addEventListener("click", (e) => {
  const remove = e.target.closest("[data-remove]");
  if (remove) editChips(remove.closest(".chips"), (list) => list.filter((id) => id !== remove.dataset.remove));
});
document.addEventListener("change", (e) => {
  if (!e.target.classList.contains("chip-add")) return;
  const id = e.target.value.trim();
  e.target.value = "";
  if (id.includes(".")) editChips(e.target.closest(".chips"), (list) => list.includes(id) ? list : [...list, id]);
});

// what a field's control holds now, undefined when it's blank
function readField(form, f) {
  if (f.kind === "calendars" || f.kind === "feeds") return [...form.querySelectorAll(`[name="${f.key}"]:checked`)].map((c) => c.value);
  const el = form.querySelector(`[name="${f.key}"]`);
  if (f.kind === "toggle") return el.checked;
  if (f.kind === "entities") return JSON.parse(el.value || "[]");
  const v = el.value.trim();
  if (!v) return undefined;
  return f.kind === "number" ? Number(v) : v;
}

// the hub's own settings, the same sections and fields as its settings screen. edits are kept
// across sections until save or cancel, the hub checks them the same way the screen does.
// a list section (calendars) edits one item at a time, done puts it back in the list
let hubSettings = null, settingsEdits = {}, settingsSection = 0, editingItem = null;

async function loadSettings() {
  try {
    hubSettings = JSON.parse(await api("/api/settings"));
    settingsEdits = structuredClone(hubSettings.values);
    editingItem = null;
    drawSettingsSections();
  } catch (e) { say("settings-message", e.message, true); }
}

function drawSettingsSections() {
  const list = $("settings-sections");
  list.innerHTML = hubSettings.sections.map((s, i) => `<button type="button" data-i="${i}" class="${i === settingsSection ? "on" : ""}">${s.name}</button>`).join("");
  list.querySelectorAll("button").forEach((b) => b.onclick = () => { settingsSection = Number(b.dataset.i); editingItem = null; drawSettingsSections(); });
  drawSettingsFields();
}

const shownWith = (f, values) => Object.entries(f.showIf || {}).every(([k, want]) => String(values[k]) === want);

// fields over one object of values, kept in step as they're edited, hidden ones come and go
function bindFields(form, fields, values) {
  form.innerHTML += fields.map((f) => `<div data-key="${f.key}">${field(f, values)}</div>`).join("");
  const refresh = () => {
    fields.forEach((f) => { form.querySelector(`[data-key="${f.key}"]`).hidden = !shownWith(f, values); });
    form.querySelectorAll(".swatches").forEach((box) => {
      const typed = box.querySelector("input").value.trim().toLowerCase();
      box.querySelectorAll("[data-c]").forEach((b) => b.classList.toggle("on", b.dataset.c === typed));
    });
  };
  form.oninput = form.onchange = () => {
    for (const f of fields) {
      const v = readField(form, f);
      if (v === undefined) delete values[f.key]; else values[f.key] = v;
    }
    refresh();
  };
  // a swatch fills the colour box beside it
  form.onclick = (e) => {
    const b = e.target.closest("[data-c]");
    if (!b) return;
    b.parentElement.querySelector("input").value = b.dataset.c;
    form.oninput();
  };
  refresh();
}

function drawSettingsFields() {
  const section = hubSettings.sections[settingsSection], form = $("settings-form");
  $("settings-save").hidden = $("settings-cancel").hidden = editingItem !== null;
  if (editingItem !== null) return drawItem(section);
  form.innerHTML = `<h2>${section.name}</h2>`;
  // empty lists aren't sent, a list section has no fields of its own
  bindFields(form, section.fields || [], settingsEdits);
  if (section.items) {
    const items = settingsEdits[section.id] || [];
    form.insertAdjacentHTML("beforeend", (items.length ? "" : `<p class="muted">no ${section.name} yet</p>`) +
      items.map((it, i) => `<button type="button" class="item" data-item="${i}">${it.name || "unnamed " + section.itemName}</button>`).join("") +
      `<div class="bar"><button type="button" data-item="-1">add a ${section.itemName}</button></div>`);
    form.querySelectorAll("[data-item]").forEach((b) => b.onclick = () => { editingItem = Number(b.dataset.item); drawSettingsFields(); });
  }
  if (section.actions?.length) {
    form.insertAdjacentHTML("beforeend", `<div class="bar">` + section.actions.map((a) => `<button type="button" data-action="${a.value}">${a.label}</button>`).join("") + `</div>`);
    form.querySelectorAll("[data-action]").forEach((b) => b.onclick = () => runAction(b.dataset.action));
  }
}

function drawItem(section) {
  const form = $("settings-form"), items = settingsEdits[section.id] || [];
  const item = structuredClone(editingItem >= 0 ? items[editingItem] : hubSettings.newItems[section.id]);
  form.innerHTML = `<h2>${item.name || "new " + section.itemName}</h2>`;
  bindFields(form, section.items, item);
  form.insertAdjacentHTML("beforeend", `<div class="bar"><button type="button" id="item-done" class="primary">done</button>` +
    (editingItem >= 0 ? `<button type="button" id="item-remove">remove</button>` : "") + `<button type="button" id="item-back">back</button></div>`);
  const close = () => { editingItem = null; drawSettingsFields(); };
  $("item-done").onclick = () => {
    const next = [...items];
    if (editingItem >= 0) next[editingItem] = item; else next.push(item);
    settingsEdits[section.id] = next;
    close();
  };
  if (editingItem >= 0) $("item-remove").onclick = () => { settingsEdits[section.id] = items.filter((_, i) => i !== editingItem); close(); };
  $("item-back").onclick = close;
}

async function runAction(id) {
  try {
    Object.assign(settingsEdits, JSON.parse(await api("/api/settings/action", { method: "POST", body: id })));
    say("settings-message", "", false);
    drawSettingsFields();
  } catch (e) { say("settings-message", e.message, true); }
}

$("settings-save").onclick = async () => {
  try {
    await api("/api/settings", { method: "PUT", body: JSON.stringify(settingsEdits) });
    say("settings-message", "saved, the hub uses it straight away", false);
    loadSettings();
    sources = JSON.parse(await api("/api/sources"));
  } catch (e) { say("settings-message", e.message, true); }
};
$("settings-cancel").onclick = () => {
  settingsEdits = structuredClone(hubSettings.values);
  say("settings-message", "", false);
  drawSettingsFields();
};


// themes. the built in ones come from the hub, the user's own live in config.themes as overrides
// on top of another theme, so they save, undo and show unsaved changes like the layout does
let presets = [], themePicked = null, themeDash = null;

const COLOR_ROLES = [
  ["primary", "primary, buttons and things that are on"], ["onPrimary", "text on primary"],
  ["background", "background"], ["onBackground", "text on the background"],
  ["surface", "tiles"], ["onSurface", "text on tiles"],
  ["error", "errors"], ["onError", "text on errors"],
  ["secondary", "secondary"], ["onSecondary", "text on secondary"],
  ["primaryVariant", "primary variant"], ["secondaryVariant", "secondary variant"],
];

// reading never adds an empty list, that would show as an unsaved change
const userThemes = () => config.themes || [];
const addTheme = (t) => (config.themes ||= []).push(t);
const isPreset = (id) => presets.some((p) => p.id === id);
const themeIds = () => [...presets.map((p) => p.id), ...userThemes().map((t) => t.id)];

// objects merge key by key, anything else (a colour, the palette) replaces
function mergeTheme(base, over) {
  const out = { ...base };
  for (const [k, v] of Object.entries(over)) {
    out[k] = v && typeof v === "object" && !Array.isArray(v) && base[k] && typeof base[k] === "object" ? mergeTheme(base[k], v) : v;
  }
  return out;
}

// a theme with everything it inherits filled in, null when its chain is broken
function resolveTheme(id, seen = new Set()) {
  const preset = presets.find((p) => p.id === id);
  if (preset) return preset;
  const own = userThemes().find((t) => t.id === id);
  if (!own || seen.has(id)) return null;
  seen.add(id);
  const base = resolveTheme(own.extends, seen);
  if (!base) return null;
  const { extends: _, ...rest } = own;
  return mergeTheme(base, { name: own.name || own.id, ...rest });
}

const themeName = (id) => resolveTheme(id)?.name || id;

// the themes a theme may build on, anything but itself and the ones built on it
function possibleBases(id) {
  const leadsTo = (t, target, seen = new Set()) => {
    if (t === target) return true;
    const own = userThemes().find((x) => x.id === t);
    if (!own || seen.has(t)) return false;
    seen.add(t);
    return leadsTo(own.extends, target, seen);
  };
  return themeIds().filter((t) => !leadsTo(t, id));
}

function drawThemes() {
  if (!config || !presets.length) return;
  if (themeDash === null || themeDash >= config.dashboards.length) themeDash = dash;
  if (!themePicked || !themeIds().includes(themePicked)) themePicked = config.dashboards[themeDash].theme.dark;
  const d = config.dashboards[themeDash];
  const options = (chosen) => themeIds().map((id) => `<option value="${esc(id)}" ${id === chosen ? "selected" : ""}>${esc(themeName(id))}</option>`).join("");
  $("theme-dashboard").innerHTML = config.dashboards.map((x, i) => `<option value="${i}" ${i === themeDash ? "selected" : ""}>${esc(x.name)}</option>`).join("");
  $("theme-mode").value = d.theme.mode;
  $("theme-light").innerHTML = options(d.theme.light);
  $("theme-dark").innerHTML = options(d.theme.dark);
  $("theme-list").innerHTML = themeIds().map((id) =>
    `<button type="button" data-theme="${esc(id)}" class="${id === themePicked ? "on" : ""}">${esc(themeName(id))}${isPreset(id) ? " (built in)" : ""}</button>`).join("");
  $("theme-list").querySelectorAll("[data-theme]").forEach((b) => b.onclick = () => { themePicked = b.dataset.theme; drawThemes(); });
  $("themes-dirty").textContent = dirty() ? "unsaved changes" : "";
  drawThemeEditor();
}

function drawThemeEditor() {
  const box = $("theme-editor"), id = themePicked, t = resolveTheme(id);
  delete box.dataset.remembered;
  if (!t) { box.innerHTML = `<p class="error">${esc(id)} builds on a theme that isn't there</p>`; return; }
  const own = userThemes().find((x) => x.id === id);
  const preview = themePreview(t);
  if (!own) {
    box.innerHTML = `<h2>${esc(t.name)}</h2><p class="muted">built in, duplicate it to make your own version</p>${preview}` +
      `<div class="bar"><button type="button" id="theme-copy">duplicate</button></div>`;
    $("theme-copy").onclick = () => copyTheme(id);
    return;
  }
  const colors = own.colors || {};
  box.innerHTML = `<h2>${esc(t.name)}</h2>${preview}` +
    `<label>name<input id="theme-name" value="${esc(own.name || "")}" placeholder="${esc(own.id)}"></label>` +
    `<label>builds on<select id="theme-base">${possibleBases(id).map((b) => `<option value="${esc(b)}" ${b === own.extends ? "selected" : ""}>${esc(themeName(b))}</option>`).join("")}</select></label>` +
    `<h2>colours</h2><div class="roles">` + COLOR_ROLES.map(([key, label]) =>
      `<label class="role"><input type="color" data-role="${key}" value="${t.colors[key].slice(-6).padStart(7, "#")}">` +
      `<span>${label}<small>${colors[key] ? "this theme's own" : "from " + esc(themeName(own.extends))}</small></span>` +
      (colors[key] ? `<button type="button" data-reset="${key}">use inherited</button>` : "") + `</label>`).join("") + `</div>` +
    `<h2>palette</h2><p class="muted">calendars set to auto take these in order</p><div class="swatches">` +
    t.palette.map((c, i) => `<span class="chip"><input type="color" data-palette="${i}" value="${c.slice(-6).padStart(7, "#")}"><button type="button" data-unpalette="${i}">×</button></span>`).join("") +
    `<button type="button" id="palette-add">add a colour</button>${own.palette ? `<button type="button" id="palette-reset">use inherited</button>` : ""}</div>` +
    `<div class="bar"><button type="button" id="theme-copy">duplicate</button><button type="button" id="theme-delete">delete</button></div>` +
    `<p class="muted">fonts, sizes, spacing and motion can be changed in the json tab, under themes</p>`;
  // one undo step per visit to the editor, not one per colour dragged through
  box.onfocusin = () => { if (!box.dataset.remembered) { remember(); box.dataset.remembered = "1"; } };
  const changed = () => { drawThemeList(); $("themes-dirty").textContent = dirty() ? "unsaved changes" : ""; box.querySelector(".preview").outerHTML = themePreview(resolveTheme(id)); };
  $("theme-name").oninput = (e) => { if (e.target.value.trim()) own.name = e.target.value.trim(); else delete own.name; changed(); };
  $("theme-base").onchange = (e) => { own.extends = e.target.value; drawThemes(); };
  box.querySelectorAll("[data-role]").forEach((input) => input.oninput = () => {
    own.colors = { ...(own.colors || {}), [input.dataset.role]: input.value };
    changed();
    input.closest(".role").querySelector("small").textContent = "this theme's own";
  });
  box.querySelectorAll("[data-role]").forEach((input) => input.onchange = () => drawThemeEditor());
  box.querySelectorAll("[data-reset]").forEach((b) => b.onclick = () => {
    remember();
    delete own.colors[b.dataset.reset];
    if (!Object.keys(own.colors).length) delete own.colors;
    drawThemes();
  });
  const palette = () => [...box.querySelectorAll("[data-palette]")].map((i) => i.value);
  box.querySelectorAll("[data-palette]").forEach((input) => input.oninput = () => { own.palette = palette(); changed(); });
  box.querySelectorAll("[data-unpalette]").forEach((b) => b.onclick = () => {
    const list = palette();
    if (list.length < 2) return say("themes-message", "a palette needs at least one colour", true);
    remember();
    own.palette = list.filter((_, i) => i !== Number(b.dataset.unpalette));
    drawThemes();
  });
  $("palette-add").onclick = () => { remember(); own.palette = [...palette(), t.colors.primary]; drawThemes(); };
  if (own.palette) $("palette-reset").onclick = () => { remember(); delete own.palette; drawThemes(); };
  $("theme-copy").onclick = () => copyTheme(id);
  $("theme-delete").onclick = () => deleteTheme(id);
}

function drawThemeList() {
  $("theme-list").querySelectorAll("[data-theme]").forEach((b) => {
    b.textContent = themeName(b.dataset.theme) + (isPreset(b.dataset.theme) ? " (built in)" : "");
  });
}

// a few tiles in the theme's own colours, so a change shows before it's saved
function themePreview(t) {
  const c = t.colors, r = t.radii.medium + "px";
  return `<div class="preview" style="background: ${c.background}; color: ${c.onBackground}; border-radius: ${r}">` +
    `<div class="tile" style="background: ${c.surface}; color: ${c.onSurface}; border-radius: ${r}">` +
    `<span style="color: ${c.primary}">●</span> lounge lamp<small>on, 80%</small></div>` +
    `<div class="tile" style="background: ${c.surface}; color: ${c.onSurface}; border-radius: ${r}">notifications` +
    `<span class="pill" style="background: ${c.primary}; color: ${c.onPrimary}; border-radius: ${r}">clear all</span>` +
    `<small style="color: ${c.error}">couldn't reach the calendar</small></div>` +
    `<div class="tile" style="background: ${c.surface}; color: ${c.onSurface}; border-radius: ${r}">calendars<span class="dots">` +
    t.palette.map((p) => `<i style="background: ${p}"></i>`).join("") + `</span></div></div>`;
}

function newThemeId(name) {
  return uniqueId(themeIds(), name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "theme");
}

function copyTheme(id) {
  const name = themeName(id) + " copy";
  const own = userThemes().find((t) => t.id === id);
  remember();
  const copy = own ? { ...structuredClone(own), id: newThemeId(name), name } : { id: newThemeId(name), name, extends: id };
  addTheme(copy);
  themePicked = copy.id;
  drawThemes();
}

function deleteTheme(id) {
  const users = config.dashboards.filter((d) => d.theme.light === id || d.theme.dark === id);
  if (users.length) return say("themes-message", `${users.map((d) => d.name).join(", ")} uses this theme, pick another one there first`, true);
  const children = userThemes().filter((t) => t.extends === id);
  if (children.length) return say("themes-message", `${children.map((t) => t.name || t.id).join(", ")} builds on this theme`, true);
  if (!confirm(`delete ${themeName(id)}?`)) return;
  remember();
  config.themes = userThemes().filter((t) => t.id !== id);
  if (!config.themes.length) delete config.themes;
  themePicked = null;
  drawThemes();
}

$("theme-new").onclick = () => {
  const name = prompt("name for the new theme");
  if (!name) return;
  remember();
  const base = config.dashboards[themeDash ?? dash].theme.dark;
  addTheme({ id: newThemeId(name), name, extends: base });
  themePicked = userThemes().at(-1).id;
  drawThemes();
};
$("theme-dashboard").onchange = (e) => { themeDash = Number(e.target.value); drawThemes(); };
for (const key of ["mode", "light", "dark"]) {
  $("theme-" + key).onchange = (e) => {
    remember();
    config.dashboards[themeDash].theme[key] = e.target.value;
    drawThemes();
  };
}
$("themes-save").onclick = () => save(JSON.stringify(config, null, 2), "themes-message");

const uniqueId = (taken, base) => { let n = 1, id = base; while (taken.includes(id)) id = base + ++n; return id; };

$("dashboard").onchange = (e) => { dash = Number(e.target.value); page = 0; picked = -1; render(); };
$("dash-new").onclick = () => {
  const name = prompt("name for the new dashboard");
  if (!name) return;
  const like = current();
  remember();
  config.dashboards.push({ id: uniqueId(config.dashboards.map((d) => d.id), name.toLowerCase().replace(/[^a-z0-9]+/g, "-")), name,
    theme: board().theme, pages: [{ id: "main", columns: like.columns, rows: like.rows, widgets: [] }] });
  dash = config.dashboards.length - 1; page = 0; picked = -1; render();
};
$("dash-copy").onclick = () => {
  const copy = JSON.parse(JSON.stringify(board()));
  copy.id = uniqueId(config.dashboards.map((d) => d.id), copy.id + "-copy");
  copy.name = copy.name + " copy";
  remember();
  config.dashboards.push(copy);
  dash = config.dashboards.length - 1; render();
};
$("dash-delete").onclick = () => {
  if (!confirm(`delete ${board().name}?`)) return;
  remember();
  const gone = config.dashboards.splice(dash, 1)[0];
  if (gone.id === config.activeDashboard) config.activeDashboard = config.dashboards[0].id;
  dash = 0; page = 0; picked = -1; render();
};
$("dash-active").onclick = () => { remember(); config.activeDashboard = board().id; render(); };
$("page-prev").onclick = () => { page--; picked = -1; render(); };
$("page-next").onclick = () => { page++; picked = -1; render(); };
$("page-add").onclick = () => {
  const like = current(), pages = board().pages;
  remember();
  const added = { id: uniqueId(pages.map((p) => p.id), "page" + (pages.length + 1)), columns: like.columns, rows: like.rows, widgets: [] };
  if (like.density) added.density = like.density;
  pages.push(added);
  page = pages.length - 1; picked = -1; render();
};
$("page-remove").onclick = () => { remember(); board().pages.splice(page, 1); picked = -1; render(); };

async function save(text, messageId) {
  try {
    await api("/api/config", { method: "PUT", body: text });
    config = JSON.parse(text);
    savedText = JSON.stringify(config);
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

start();
