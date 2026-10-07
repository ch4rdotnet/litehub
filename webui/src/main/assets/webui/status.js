// renders /api/status, read only
const esc = (s) => String(s ?? "").replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
const row = (k, v, bad) => `<tr><td>${esc(k)}</td><td class="${bad ? "error" : ""}">${esc(v)}</td></tr>`;
const sources = (list) => list.length ? `<table>${list.map((s) =>
  row(s.name, s.error ? `failing, ${s.error}` : s.last_good ? `ok, ${s.last_good}` : "not fetched yet", !!s.error)).join("")}</table>` : "<p>none</p>";

// the same rows and labels as the device's own settings screen
const device = (d) => `<h2>device</h2><table>${row("made by", d.manufacturer)}${row("model", `${d.model} (${d.device})`)}${row("soc", d.soc)}` +
  `${row("cpu", `${d.cpu_cores} cores, ${d.abi}`)}${row("android", `${d.android} (api ${d.api})`)}${row("ram", `${d.ram_mb} mb, ${d.ram_free_mb} mb free`)}` +
  `${row("tier", d.tier + (d.low_ram_flag ? ", firmware says low ram" : ""))}${row("screen", `${d.screen} at ${d.density_dpi}dpi, ${d.refresh_hz}hz`)}` +
  `${row("ip address", d.ip || "no network")}${row("free storage", d.storage_free_mb + " mb")}${row("light sensor", d.light_sensor ? "yes" : "none")}` +
  `${row("cameras", d.cameras)}${row("device up for", d.device_uptime)}</table>`;

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
      device(s.device) +
      `<h2>recent log</h2><pre>${esc(s.log.join("\n")) || "nothing yet"}</pre>`;
  } catch (e) {
    document.getElementById("out").innerHTML = `<p class="error">couldn't reach the hub, ${esc(e.message)}</p>`;
  }
}
refresh();
setInterval(refresh, 10000);
