// renders /api/status, read only
const esc = (s) => String(s ?? "").replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
const row = (k, v, bad) => `<tr><td>${k}</td><td class="${bad ? "error" : ""}">${esc(v)}</td></tr>`;
const sources = (list) => list.length ? `<table>${list.map((s) =>
  row(s.name, s.error ? `failing, ${s.error}` : s.last_good ? `ok, ${s.last_good}` : "not fetched yet", !!s.error)).join("")}</table>` : "<p>none</p>";

async function refresh() {
  try {
    const s = await (await fetch("/api/status")).json();
    const ha = s.home_assistant;
    document.getElementById("out").innerHTML =
      `<table>${row("version", s.version)}${row("up for", s.uptime)}${row("dashboard", s.active_dashboard)}${row("page", s.page)}` +
      `${row("screen", s.screen_on ? "on" : "off")}${row("memory", s.memory_mb + " mb")}${row("cpu", s.cpu_percent + "%")}</table>` +
      `<h2>home assistant</h2><table>${row("connection", ha.state, ha.state !== "connected")}` +
      `${row("latency", ha.latency_ms == null ? "unknown" : ha.latency_ms + " ms")}${row("companion device", ha.companion || "not registered")}</table>` +
      `<h2>calendars</h2>${sources(s.calendars)}<h2>feeds</h2>${sources(s.feeds)}` +
      `<h2>recent log</h2><pre>${esc(s.log.join("\n")) || "nothing yet"}</pre>`;
  } catch (e) {
    document.getElementById("out").innerHTML = `<p class="error">couldn't reach the hub, ${esc(e.message)}</p>`;
  }
}
refresh();
setInterval(refresh, 10000);
