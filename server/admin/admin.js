"use strict";
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

/* ---------- tiny safe DOM builder (never uses innerHTML) ---------- */
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "text") el.textContent = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return el;
}
const icon = name => { const s = document.createElementNS("http://www.w3.org/2000/svg", "svg"); s.setAttribute("class", "ico"); const u = document.createElementNS("http://www.w3.org/2000/svg", "use"); u.setAttribute("href", "#i-" + name); s.append(u); return s; };
const clear = el => { el.textContent = ""; return el; };
const money = n => Number(n || 0).toLocaleString("en-LK");
const rs = n => "Rs. " + money(n);
const STOCK = {in: ["In stock", "ok"], out: ["Out of stock", "bad"], pre: ["Pre-order", "warn"]};
const TAGS = {hot: ["Hot deal", "brand"], new: ["New", "info"], best: ["Best seller", "ok"]};

function ago(iso) {
  const t = new Date(iso).getTime(); if (!t) return "";
  const s = Math.max(1, Math.round((Date.now() - t) / 1000));
  if (s < 60) return "just now";
  if (s < 3600) return Math.round(s / 60) + " min ago";
  if (s < 86400) return Math.round(s / 3600) + " h ago";
  if (s < 86400 * 14) return Math.round(s / 86400) + " d ago";
  return new Date(t).toLocaleDateString("en-LK", {day: "numeric", month: "short", year: "numeric"});
}
const stamp = iso => { const d = new Date(iso); return isNaN(d) ? "" : d.toLocaleString("en-LK", {dateStyle: "medium", timeStyle: "short"}); };
const initials = name => (String(name || "?").trim().split(/\s+/).slice(0, 2).map(w => w[0]).join("") || "?").toUpperCase();
const slug = v => { let t = String(v || "").normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase().replace(/&/g, " and ").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, ""); if (t.length > 70) t = t.slice(0, 70).replace(/-+$/, ""); return t || "item"; };
function waLink(phone, text) {
  let d = String(phone || "").replace(/\D/g, "");
  if (d.startsWith("0")) d = "94" + d.slice(1);          // local Sri Lankan number
  return "https://wa.me/" + d + (text ? "?text=" + encodeURIComponent(text) : "");
}

/* ---------- state ---------- */
let S = null, SHOP = null, REQUESTS = [], QUOTES = [], dirty = false, currentView = "dashboard";

/* ---------- api ---------- */
async function call(path, method = "GET", body, form) {
  const opt = {method, credentials: "same-origin", headers: {"X-Requested-With": "admin"}};
  if (form) opt.body = form;
  else if (body !== undefined) { opt.headers["Content-Type"] = "application/json"; opt.body = JSON.stringify(body); }
  const r = await fetch(path, opt);
  const j = await r.json().catch(() => ({}));
  if (r.status === 401 && !path.endsWith("/login")) { showLogin("Your session has ended. Please sign in again."); throw new Error("Please sign in."); }
  if (!r.ok) throw new Error(j.error || "Request failed (" + r.status + ")");
  return j;
}

/* ---------- toasts / confirm ---------- */
function toast(text, kind = "ok") {
  const t = h("div", {class: "toast " + kind}, icon(kind === "ok" ? "check" : "alert"), text);
  $("#toasts").append(t);
  setTimeout(() => t.remove(), kind === "ok" ? 3200 : 6000);
}
function confirmBox(title, text, okLabel = "Delete") {
  return new Promise(resolve => {
    const d = $("#confirmDialog");
    $("#confirmTitle").textContent = title; $("#confirmText").textContent = text; $("#confirmOk").textContent = okLabel;
    d.addEventListener("close", () => resolve(d.returnValue === "ok"), {once: true});
    d.returnValue = "cancel"; d.showModal();
  });
}
document.addEventListener("click", e => {
  const c = e.target.closest("[data-close]");
  if (c) c.closest("dialog").close();
  const d = e.target;
  if (d instanceof HTMLDialogElement && d.open) { const r = d.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) d.close(); }
});

/* ---------- login / session ---------- */
function showLogin(msg) {
  $("#app").hidden = true; $("#login").hidden = false;
  $("#loginMsg").textContent = msg || ""; $("#pw").value = "";
  setDirty(false);
}
function showApp() { $("#login").hidden = true; $("#app").hidden = false; }
$("#pwToggle").onclick = () => { const i = $("#pw"); i.type = i.type === "password" ? "text" : "password"; };
$("#loginForm").onsubmit = async e => {
  e.preventDefault(); $("#loginMsg").textContent = "";
  const b = $("#loginBtn"); b.disabled = true; b.textContent = "Signing in…";
  try { await call("/api/admin/login", "POST", {password: $("#pw").value}); $("#pw").value = ""; await boot(); }
  catch (err) { $("#loginMsg").textContent = err.message; }
  finally { b.disabled = false; b.textContent = "Sign in"; }
};
$("#logout").onclick = async () => { try { await call("/api/admin/logout", "POST", {}); } catch (e) { /* already signed out */ } showLogin(); };

/* ---------- navigation ---------- */
const TITLES = {dashboard: "Dashboard", requests: "Quote requests", quotes: "Instant quotes", products: "Products", categories: "Categories",
  visitors: "Visitors", pricing: "Instant quote pricing", estimator: "STL estimator pricing", materials: "Materials", limits: "Limits & customer text"};
const SETTINGS_VIEWS = new Set(["pricing", "estimator", "materials", "limits"]);
function route() {
  const name = (location.hash || "#dashboard").slice(1);
  currentView = TITLES[name] ? name : "dashboard";
  $$(".view").forEach(v => v.hidden = v.id !== "view-" + currentView);
  $$(".side-nav a").forEach(a => a.dataset.nav === currentView ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
  $("#viewTitle").textContent = TITLES[currentView];
  document.title = TITLES[currentView] + " – 3DSHOP.LK Admin";
  const isSettings = SETTINGS_VIEWS.has(currentView);
  $("#save").hidden = !isSettings; $("#discard").hidden = !isSettings;
  closeMenu(); window.scrollTo(0, 0);
  if (currentView === "visitors") loadVisits().catch(e => toast(e.message, "bad"));
}
window.addEventListener("hashchange", () => { if (!$("#app").hidden) route(); });
const closeMenu = () => { $("#sidebar").classList.remove("open"); $("#scrim").hidden = true; $("#menuBtn").setAttribute("aria-expanded", "false"); };
$("#menuBtn").onclick = () => { const o = $("#sidebar").classList.toggle("open"); $("#scrim").hidden = !o; $("#menuBtn").setAttribute("aria-expanded", String(o)); };
$("#scrim").onclick = closeMenu;
document.addEventListener("keydown", e => { if (e.key === "Escape") closeMenu(); });
$$("[data-go]").forEach(b => b.addEventListener("click", () => {
  location.hash = "#" + b.dataset.go;
  if (b.dataset.action === "new-product") setTimeout(() => openProduct(null), 50);
  if (b.dataset.action === "new-category") setTimeout(() => openCategory(null), 50);
}));

/* ---------- settings form ---------- */
const PRICING = [
  ["usdToLkr", "USD → LKR rate", "Applied to the supplier price", .01],
  ["markupPercent", "Markup %", "Your margin on top of the supplier price", .1],
  ["fixedFeeLkr", "Handling fee (Rs.)", "Added to every quote", 1],
  ["flatShippingLkr", "Shipping (Rs.)", "Flat amount added once per quote", 1],
  ["minOrderLkr", "Minimum order (Rs.)", "Quotes never go below this", 1],
  ["roundToLkr", "Round to nearest (Rs.)", "For example 10 or 50", 1]
];
const MATERIAL_COST = [["costPlaPlus", "PLA+", "plaPlus"], ["costAbsPlus", "ABS+", "absPlus"], ["costTpu", "TPU", "tpu"], ["costPetg", "PETG", "petg"], ["costAsa", "ASA", "asa"]];
const ESTIMATOR = [
  ["printerCostLkrPerMinute", "Printer + electricity (Rs./min)", "Average of your machine totals", .01],
  ["estimatorPrintVolumeCm3PerMinute", "Material throughput (cm³/min)", "Required for automatic print-time estimates", .01],
  ["printingMargin", "Printing margin multiplier", "1.4 = 40% on top of cost", .01],
  ["fastenerMargin", "Fastener margin multiplier", "", .01],
  ["fastenerCostLkrPerPart", "Fastener cost per part (Rs.)", "0 when not applicable", .01],
  ["labourMonthlySalaryLkr", "Monthly salary (Rs.)", "", 1],
  ["labourCostLkrPerMinute", "Labour cost per minute (Rs.)", "", .01],
  ["estimatorSetupFeeLkr", "Estimator setup fee (Rs.)", "", 1],
  ["estimatorMinJobLkr", "Estimator minimum job (Rs.)", "", 1],
  ["estimatorRoundToLkr", "Estimator rounding (Rs.)", "", 1]
];
const LIMITS = [
  ["maxQty", "Max quantity", "", 1], ["maxFileMB", "Max file size (MB)", "", 1],
  ["maxUploadsPerHourPerIp", "Uploads per hour per visitor", "Protects your API quota", 1],
  ["maxQuotesPerHourPerIp", "Price checks per hour per visitor", "", 1], ["validityHours", "Quote valid for (hours)", "", 1]
];
const NUM = [...PRICING, ...LIMITS].map(f => f[0]);
const EST_NUM = ESTIMATOR.map(f => f[0]);

function buildFields(target, specs) {
  clear($(target)).append(...specs.map(([id, label, hint, step]) =>
    h("label", {class: "field"}, label, hint ? h("small", {text: hint}) : null, h("input", {type: "number", id, step, min: "0", inputmode: "decimal"}))));
}
buildFields("#fields-pricing", PRICING);
buildFields("#fields-estimator", ESTIMATOR);
buildFields("#fields-limits", LIMITS);
clear($("#fields-materialCost")).append(...MATERIAL_COST.map(([id, label]) =>
  h("label", {class: "field"}, label, h("input", {type: "number", id, step: .01, min: "0", inputmode: "decimal"}))));

function setDirty(v) {
  dirty = v;
  $("#dirtyChip").hidden = !v || !SETTINGS_VIEWS.has(currentView);
  $("#save").disabled = !v;
}
window.addEventListener("beforeunload", e => { if (dirty) { e.preventDefault(); e.returnValue = ""; } });
document.addEventListener("input", e => {
  if (!e.target.closest(".settings")) return;
  setDirty(true); preview();
});
$("#quotesEnabled").addEventListener("change", () => { setDirty(true); preview(); });

function fillSettings() {
  $("#quotesEnabled").checked = S.quotesEnabled;
  NUM.forEach(k => $("#" + k).value = S[k]);
  EST_NUM.forEach(k => $("#" + k).value = S[k] ?? 0);
  MATERIAL_COST.forEach(([id, , key]) => $("#" + id).value = S.materialCostLkrPerGram?.[key] ?? 0);
  $("#customerNote").value = S.customerNote || "";
  const tb = clear($("#matRows"));
  $("#noMats").hidden = S.knownMaterials.length > 0;
  S.knownMaterials.forEach(m => {
    const cb = h("input", {type: "checkbox", "data-id": m.id, "aria-label": "Show " + (m.name || m.id)});
    cb.checked = !S.disabledMaterials.includes(m.id);
    const num = h("input", {type: "number", step: .1, "data-id": m.id, placeholder: "global", "aria-label": "Markup override for " + (m.name || m.id)});
    if (S.materialMarkup[m.id] != null) num.value = S.materialMarkup[m.id];
    tb.append(h("tr", {}, h("td", {}, cb), h("td", {class: "cell-main", text: m.name || m.id}), h("td", {}, num)));
  });
  setDirty(false); preview();
}
function collect() {
  const o = {quotesEnabled: $("#quotesEnabled").checked, customerNote: $("#customerNote").value, materialMarkup: {}, disabledMaterials: []};
  [...NUM, ...EST_NUM].forEach(k => o[k] = parseFloat($("#" + k).value));
  o.materialCostLkrPerGram = Object.fromEntries(MATERIAL_COST.map(([id, , key]) => [key, parseFloat($("#" + id).value)]));
  $$("#matRows input[type=checkbox]").forEach(cb => { if (!cb.checked) o.disabledMaterials.push(cb.dataset.id); });
  $$("#matRows input[type=number]").forEach(n => { if (n.value !== "") o.materialMarkup[n.dataset.id] = parseFloat(n.value); });
  return o;
}
function preview() {
  if (!S) return;
  const o = collect(), cost = 10;
  let v = cost * o.usdToLkr * (1 + o.markupPercent / 100) + o.fixedFeeLkr;
  v = Math.max(v, o.minOrderLkr) + o.flatShippingLkr;
  v = Math.round(v / o.roundToLkr) * o.roundToLkr;
  $("#preview").textContent = isFinite(v) ? `Example: a part that costs $${cost} from the supplier would be quoted at ${rs(v)}.` : "";
  if (!(o.estimatorPrintVolumeCm3PerMinute > 0)) { $("#estimatorPreview").textContent = "Add a measured material throughput before customer estimates can be priced."; return; }
  const minutes = 10 / o.estimatorPrintVolumeCm3PerMinute;
  const material = 10 * o.materialCostLkrPerGram.plaPlus;
  const machine = minutes * (o.printerCostLkrPerMinute + o.labourCostLkrPerMinute);
  const raw = (material + machine) * o.printingMargin + o.fastenerCostLkrPerPart * o.fastenerMargin + o.estimatorSetupFeeLkr;
  const rounded = Math.round(Math.max(o.estimatorMinJobLkr, raw) / o.estimatorRoundToLkr) * o.estimatorRoundToLkr;
  $("#estimatorPreview").textContent = isFinite(rounded) ? `Example: a 10 g PLA+ part is estimated at ${rs(rounded)}.` : "Check the estimator pricing values.";
}
$("#save").onclick = async () => {
  const body = collect();
  if ([...NUM, ...EST_NUM].some(k => !Number.isFinite(body[k])) || Object.values(body.materialCostLkrPerGram).some(v => !Number.isFinite(v))) { toast("Fill in every number field before saving.", "bad"); return; }
  $("#save").disabled = true;
  try { await call("/api/admin/settings", "PUT", body); S = await call("/api/admin/settings"); fillSettings(); renderDashboard(); toast("Saved. New quotes use these values straight away."); }
  catch (e) { toast(e.message, "bad"); $("#save").disabled = false; }
};
$("#discard").onclick = () => { fillSettings(); toast("Changes discarded."); };

/* ---------- dashboard ---------- */
function kpi(iconName, label, value, sub, cls) {
  return h("div", {class: "kpi " + (cls || "")}, h("div", {class: "k-label"}, h("span", {class: "k-icon"}, icon(iconName)), label), h("div", {class: "k-value", text: value}), h("div", {class: "k-sub", text: sub}));
}
function renderDashboard() {
  if (!S || !SHOP) return;
  const week = Date.now() - 7 * 864e5;
  const recent = REQUESTS.filter(r => new Date(r.time).getTime() > week).length;
  const oos = SHOP.products.filter(p => p.stock === "out").length;
  const monthQuotes = QUOTES.filter(q => new Date(q.time).getTime() > Date.now() - 30 * 864e5);
  clear($("#kpis")).append(
    kpi("inbox", "Quote requests", String(REQUESTS.length), recent + " in the last 7 days"),
    kpi("receipt", "Instant quotes", String(QUOTES.length), money(monthQuotes.reduce((a, q) => a + Number(q.quotedLkr || 0), 0)) + " Rs. quoted in 30 days"),
    kpi("box", "Products", String(SHOP.products.length), oos + " out of stock · " + Object.keys(SHOP.categories).length + " categories"),
    kpi("coin", "Online quotes", S.quotesEnabled ? "On" : "Off", S.quotesEnabled ? "Customers can get prices" : "Paused for customers", S.quotesEnabled ? "state-on" : "state-off"));
  const dash = clear($("#dashRequests"));
  REQUESTS.slice(0, 5).forEach(r => dash.append(h("button", {class: "list-item", type: "button", onclick: () => openRequest(r)},
    h("span", {class: "avatar", text: initials(r.name)}),
    h("span", {class: "li-main"}, h("div", {class: "li-title", text: r.name}), h("div", {class: "li-sub", text: `${r.type} · ${r.phone}`})),
    h("span", {class: "li-side", text: ago(r.time)}))));
  if (!REQUESTS.length) dash.append(h("p", {class: "empty", text: "No quote requests yet."}));
  const dq = clear($("#dashQuotes"));
  QUOTES.slice(0, 5).forEach(q => dq.append(h("tr", {}, h("td", {text: ago(q.time)}), h("td", {text: q.ref}), h("td", {text: q.file}), h("td", {text: [q.material, q.color].filter(Boolean).join(" / ")}), h("td", {class: "num", text: money(q.quotedLkr)}))));
  $("#dashQuotesEmpty").hidden = QUOTES.length > 0;

  const noImg = SHOP.products.filter(p => !p.img).length, noDesc = SHOP.products.filter(p => !(p.desc || "").trim()).length;
  const shortDesc = SHOP.products.filter(p => (p.desc || "").trim() && p.desc.trim().length < 50).length;
  const items = [
    [noImg === 0, noImg === 0 ? "Every product has an image." : `${noImg} product${noImg === 1 ? " has" : "s have"} no image.`],
    [noDesc === 0, noDesc === 0 ? "Every product has a description." : `${noDesc} product${noDesc === 1 ? " has" : "s have"} no description.`],
    [shortDesc === 0, shortDesc === 0 ? "Descriptions are a good length." : `${shortDesc} description${shortDesc === 1 ? " is" : "s are"} short (under 50 characters). Aim for 100 or more.`],
    [oos === 0, oos === 0 ? "Nothing is out of stock." : `${oos} product${oos === 1 ? " is" : "s are"} out of stock.`]
  ];
  clear($("#healthList")).append(...items.map(([ok, text]) => h("li", {}, h("span", {class: ok ? "good" : "warn"}, icon(ok ? "check" : "alert")), text)));
  const badge = $("#navRequests"); badge.hidden = recent === 0; badge.textContent = String(recent);
}

/* ---------- visitors ---------- */
let VISITS = null;
function barRows(el, items, emptyText) {
  const max = Math.max(1, ...items.map(i => i.count));
  if (!items.length) { el.append(h("p", {class: "muted small", text: emptyText || "No data yet."})); return; }
  items.forEach(i => {
    const fill = h("div", {class: "bar-fill"}); fill.style.width = Math.max(3, i.count / max * 100) + "%";
    el.append(h("div", {class: "bar-row"}, h("div", {class: "bar-top"}, h("span", {text: i.name, title: i.name}), h("span", {text: money(i.count)})), h("div", {class: "bar-track"}, fill)));
  });
}
function renderVisits() {
  if (!VISITS) return;
  const t = VISITS.totals;
  clear($("#visitKpis")).append(
    kpi("eye", "Page views", money(t.views), money(t.todayViews) + " today"),
    kpi("users", "Visits", money(t.visits), "Separate visits (30 min gap)"),
    kpi("globe", "Unique visitors", money(t.uniqueIps), money(t.todayUnique) + " today · counted by IP address"),
    kpi("alert", "Bot & crawler hits", money(t.botViews), $("#visitBots").checked ? "Included in the numbers" : "Excluded from the numbers"));
  const chart = clear($("#visitChart")), maxV = Math.max(1, ...VISITS.daily.map(d => d.views));
  if (!t.views) chart.append(h("p", {class: "chart-empty", text: "No page views recorded in this period yet."}));
  else VISITS.daily.forEach(d => {
    const v = h("i", {class: "v"}), u = h("i", {class: "u"});
    v.style.height = (d.views / maxV * 100) + "%"; u.style.height = (d.unique / maxV * 100) + "%";
    const label = new Date(d.date + "T00:00:00").toLocaleDateString("en-LK", {day: "numeric", month: "short"});
    chart.append(h("div", {class: "col", title: label + ": " + d.views + " views, " + d.visits + " visits, " + d.unique + " unique visitors"}, v, u));
  });
  barRows(clear($("#visitPages")), VISITS.pages);
  barRows(clear($("#visitRefs")), VISITS.referrers, "No referrers yet.");
  const dev = clear($("#visitDevices"));
  const d1 = h("div", {class: "bars"}), d2 = h("div", {class: "bars"});
  dev.append(h("div", {class: "bar-group", text: "Devices"}), d1, h("div", {class: "bar-group gap", text: "Browsers"}), d2);
  barRows(d1, VISITS.devices); barRows(d2, VISITS.browsers);
  renderVisitIps();
  const recent = clear($("#visitRecent"));
  VISITS.recent.forEach(r => recent.append(h("tr", {},
    h("td", {}, h("div", {class: "cell-main", text: ago(r.time)}), h("div", {class: "cell-sub", text: stamp(r.time)})),
    h("td", {class: "mono", text: r.ip}), h("td", {text: r.path}),
    h("td", {}, r.device + " · " + r.browser + " ", r.bot ? h("span", {class: "badge gray", text: "bot"}) : null),
    h("td", {class: "cell-sub", text: r.referrer ? r.referrer.replace(/^https?:\/\//, "").slice(0, 40) : "Direct"}))));
}
function filteredIps() {
  const q = $("#visitSearch").value.trim().toLowerCase();
  return VISITS.ips.filter(v => !q || [v.ip, v.lastPage, v.device, v.browser].join(" ").toLowerCase().includes(q));
}
function renderVisitIps() {
  const rows = filteredIps(), tb = clear($("#visitIpRows"));
  rows.slice().sort((a, b) => b.visits - a.visits || b.views - a.views).forEach(v => tb.append(h("tr", {},
    h("td", {class: "mono cell-main", text: v.ip}), h("td", {class: "num cell-main", text: String(v.visits)}), h("td", {class: "num", text: String(v.views)}),
    h("td", {text: v.lastPage}), h("td", {}, v.device + " · " + v.browser + " ", v.bot ? h("span", {class: "badge gray", text: "bot"}) : null),
    h("td", {text: stamp(v.first)}), h("td", {}, h("div", {text: ago(v.last)}), h("div", {class: "cell-sub", text: stamp(v.last)})))));
  $("#visitIpEmpty").hidden = rows.length > 0;
}
async function loadVisits() {
  VISITS = await call("/api/admin/visits?days=" + $("#visitDays").value + ($("#visitBots").checked ? "&bots=1" : ""));
  renderVisits();
}
$("#visitDays").onchange = $("#visitBots").onchange = () => loadVisits().catch(e => toast(e.message, "bad"));
$("#reloadVisits").onclick = () => loadVisits().then(() => toast("Visitors refreshed.")).catch(e => toast(e.message, "bad"));
$("#visitSearch").oninput = () => { if (VISITS) renderVisitIps(); };
$("#exportVisits").onclick = () => {
  const cell = v => '"' + String(v ?? "").replace(/"/g, '""') + '"';
  const lines = [["IP address", "Visits", "Page views", "Last page", "Device", "Browser", "First seen", "Last seen"].map(cell).join(",")];
  filteredIps().forEach(v => lines.push([v.ip, v.visits, v.views, v.lastPage, v.device, v.browser, v.first, v.last].map(cell).join(",")));
  const a = h("a", {href: URL.createObjectURL(new Blob(["\ufeff" + lines.join("\r\n")], {type: "text/csv;charset=utf-8"})), download: "visitors-" + new Date().toISOString().slice(0, 10) + ".csv"});
  document.body.append(a); a.click(); a.remove();
};

/* ---------- quote requests ---------- */
function filteredRequests() {
  const q = $("#requestSearch").value.trim().toLowerCase(), type = $("#requestType").value;
  return REQUESTS.filter(r => (!type || r.type === type) && (!q || [r.name, r.phone, r.ref, r.details, r.material, r.colour].join(" ").toLowerCase().includes(q)));
}
function renderRequests() {
  const rows = filteredRequests(), tb = clear($("#requestRows"));
  rows.forEach(r => tb.append(h("tr", {tabindex: "0", onclick: () => openRequest(r), onkeydown: e => { if (e.key === "Enter") openRequest(r); }},
    h("td", {}, h("div", {class: "cell-main", text: ago(r.time)}), h("div", {class: "cell-sub", text: stamp(r.time)})),
    h("td", {text: r.ref}),
    h("td", {}, h("div", {class: "cell-main", text: r.name}), h("div", {class: "cell-sub", text: r.phone})),
    h("td", {}, h("span", {class: "badge " + (r.type === "Design and print" ? "info" : "brand"), text: r.type})),
    h("td", {text: [r.material, r.colour].filter(Boolean).join(" / ")}),
    h("td", {class: "num", text: String((r.files || []).length)}),
    h("td", {}, icon("eye")))));
  $("#requestsEmpty").hidden = rows.length > 0;
}
function openRequest(r) {
  $("#requestDialogTitle").textContent = "Request " + r.ref;
  const files = (r.files || []).length
    ? h("ul", {class: "file-list"}, r.files.map(f => h("li", {}, h("a", {href: f.downloadUrl, download: ""}, icon("download"), `${f.name} (${(f.size / 1048576).toFixed(1)} MB)`))))
    : h("span", {text: "No files attached"});
  const row = (k, v) => [h("dt", {text: k}), h("dd", {}, v)];
  clear($("#requestDetail")).append(h("dl", {class: "detail-grid"},
    row("Received", stamp(r.time)), row("Name", r.name), row("Phone / WhatsApp", r.phone), row("Request type", r.type),
    row("Material", r.material || "-"), row("Colour", r.colour || "-"), row("Details", r.details), row("Files", files)));
  $("#requestWhatsapp").href = waLink(r.phone, `Hi ${r.name}, this is 3DSHOP.LK about your quote request ${r.ref}.`);
  $("#requestDialog").showModal();
}
async function loadRequests() { REQUESTS = await call("/api/admin/quote-requests"); renderRequests(); renderDashboard(); }
$("#requestSearch").oninput = renderRequests; $("#requestType").onchange = renderRequests;
$("#reloadRequests").onclick = () => loadRequests().then(() => toast("Quote requests refreshed.")).catch(e => toast(e.message, "bad"));
$("#exportRequests").onclick = () => {
  const cell = v => { let t = String(v ?? "").replace(/"/g, '""'); if (/^[=+\-@\t\r]/.test(t)) t = "'" + t; return '"' + t + '"'; };   // neutralise spreadsheet formulas
  const lines = [["Received", "Ref", "Name", "Phone", "Type", "Material", "Colour", "Files", "Details"].map(cell).join(",")];
  filteredRequests().forEach(r => lines.push([r.time, r.ref, r.name, r.phone, r.type, r.material, r.colour, (r.files || []).map(f => f.name).join("; "), r.details].map(cell).join(",")));
  const a = h("a", {href: URL.createObjectURL(new Blob(["﻿" + lines.join("\r\n")], {type: "text/csv;charset=utf-8"})), download: "quote-requests-" + new Date().toISOString().slice(0, 10) + ".csv"});
  document.body.append(a); a.click(); a.remove();
};

/* ---------- instant quotes ---------- */
function renderQuotes() {
  const q = $("#quoteSearch").value.trim().toLowerCase();
  const rows = QUOTES.filter(r => !q || [r.ref, r.file, r.material, r.color].join(" ").toLowerCase().includes(q)), tb = clear($("#quoteRows"));
  rows.forEach(r => tb.append(h("tr", {}, h("td", {text: stamp(r.time)}), h("td", {text: r.ref}), h("td", {text: r.file}),
    h("td", {text: [r.material, r.color].filter(Boolean).join(" / ")}), h("td", {class: "num", text: String(r.qty)}), h("td", {class: "num", text: Number(r.jlcCostUsd).toFixed(2)}), h("td", {class: "num cell-main", text: money(r.quotedLkr)}))));
  $("#quotesEmpty").hidden = rows.length > 0;
}
async function loadQuotes() { QUOTES = (await call("/api/admin/quotes")).slice().reverse(); renderQuotes(); renderDashboard(); }
$("#quoteSearch").oninput = renderQuotes;
$("#reloadQuotes").onclick = () => loadQuotes().then(() => toast("Instant quotes refreshed.")).catch(e => toast(e.message, "bad"));

/* ---------- catalog ---------- */
async function loadCatalog() { SHOP = await call("/api/admin/shop/catalog"); renderProducts(); renderCategories(); renderDashboard(); }
async function saveCatalog(next) {
  await call("/api/admin/shop/catalog", "PUT", next);
  SHOP = await call("/api/admin/shop/catalog");
  renderProducts(); renderCategories(); renderDashboard();
}

/* ----- products ----- */
function renderProducts() {
  if (!SHOP) return;
  const catSel = $("#productCatFilter"), keep = catSel.value;
  clear(catSel).append(h("option", {value: "", text: "All categories"}), ...Object.keys(SHOP.categories).map(c => h("option", {value: c, text: c})));
  catSel.value = Object.keys(SHOP.categories).includes(keep) ? keep : "";
  const q = $("#productSearch").value.trim().toLowerCase(), cat = catSel.value, stock = $("#productStockFilter").value;
  const rows = SHOP.products.filter(p => (!cat || p.cat === cat) && (!stock || p.stock === stock) && (!q || (p.name + " " + p.desc + " " + p.cat + " " + p.sub).toLowerCase().includes(q)));
  const tb = clear($("#productRows"));
  rows.forEach(p => {
    const [stockText, stockCls] = STOCK[p.stock] || [p.stock, "gray"];
    const thumb = p.img ? h("img", {class: "thumb", src: "/images/products/" + encodeURIComponent(p.img), alt: "", width: 44, height: 44, loading: "lazy"}) : h("span", {class: "thumb"}, icon("image"));
    tb.append(h("tr", {},
      h("td", {}, h("div", {class: "prod-cell"}, thumb, h("div", {}, h("div", {class: "cell-main", text: p.name}), h("div", {class: "cell-sub", text: "ID " + p.id})))),
      h("td", {}, h("div", {text: p.cat}), p.sub ? h("div", {class: "cell-sub", text: p.sub}) : null),
      h("td", {class: "num"}, h("div", {class: "cell-main", text: rs(p.price)}), p.old ? h("div", {class: "cell-sub", text: "was " + rs(p.old)}) : null),
      h("td", {}, h("span", {class: "badge " + stockCls, text: stockText})),
      h("td", {}, h("div", {class: "badges"}, (p.tags || []).map(t => h("span", {class: "badge " + (TAGS[t]?.[1] || "gray"), text: TAGS[t]?.[0] || t})))),
      h("td", {}, h("div", {class: "row-actions"},
        h("button", {class: "icon-btn", type: "button", "aria-label": "Edit " + p.name, title: "Edit", onclick: () => openProduct(p)}, icon("edit")),
        h("a", {class: "icon-btn", "aria-label": "View " + p.name + " on website", title: "View on website", href: `/product/${p.id}-${slug(p.name)}`, target: "_blank", rel: "noopener"}, icon("external")),
        h("button", {class: "icon-btn", type: "button", "aria-label": "Delete " + p.name, title: "Delete", onclick: () => deleteProduct(p)}, icon("trash"))))));
  });
  $("#productsEmpty").hidden = rows.length > 0;
  $("#productCount").textContent = `Showing ${rows.length} of ${SHOP.products.length} products`;
}
["productSearch", "productCatFilter", "productStockFilter"].forEach(id => $("#" + id).addEventListener("input", renderProducts));

let pendingImage = null, removeImage = false, editingProduct = null;
function fillSubOptions(cat, selected) {
  const subs = SHOP.categories[cat] || [];
  clear($("#productSubcategory")).append(h("option", {value: "", text: subs.length ? "None" : "No subcategories"}), ...subs.map(s => h("option", {value: s, text: s})));
  $("#productSubcategory").value = subs.includes(selected) ? selected : "";
  $("#productSubcategory").disabled = !subs.length;
}
function setImagePreview(src) {
  const box = clear($("#imagePreview"));
  box.append(src ? h("img", {src, alt: ""}) : icon("image"));
  $("#removeImage").hidden = !src;
}
function updateSerp() {
  const name = $("#productName").value.trim() || "Product name", desc = $("#productDescription").value.trim();
  const id = editingProduct ? editingProduct.id : "new";
  $("#serpUrl").textContent = `3dshop.lk › product › ${id}-${slug(name)}`;
  const t = name.length > 42 ? name.slice(0, 41).replace(/\s+\S*$/, "") + "…" : name;
  $("#serpTitle").textContent = t + " | Buy in Sri Lanka – 3DSHOP.LK";
  const price = Number($("#productPrice").value);
  const d = (desc ? (/[.!?]$/.test(desc) ? desc : desc + ".") : name + ".") + " Buy " + name + " in Sri Lanka" + (price > 0 ? " for " + rs(price) : "") + ". Island-wide delivery, order on WhatsApp.";
  $("#serpDesc").textContent = d.length > 158 ? d.slice(0, 157).replace(/\s+\S*$/, "") + "…" : d;
  $("#descCount").textContent = String($("#productDescription").value.length);
}
["productName", "productDescription", "productPrice"].forEach(id => $("#" + id).addEventListener("input", updateSerp));
$("#productCategory").onchange = e => fillSubOptions(e.target.value, "");
$("#productImage").onchange = e => {
  const f = e.target.files[0]; pendingImage = null; removeImage = false;
  if (!f) { setImagePreview(editingProduct?.img ? "/images/products/" + encodeURIComponent(editingProduct.img) : null); return; }
  if (f.size > 5 * 1024 * 1024) { $("#productMsg").textContent = "Images must be 5 MB or smaller."; e.target.value = ""; return; }
  $("#productMsg").textContent = ""; pendingImage = f;
  const rd = new FileReader(); rd.onload = () => setImagePreview(rd.result); rd.readAsDataURL(f);
  $("#productImageCurrent").textContent = f.name;
};
$("#removeImage").onclick = () => { pendingImage = null; removeImage = true; $("#productImage").value = ""; setImagePreview(null); $("#productImageCurrent").textContent = "Image will be removed when you save."; };

function openProduct(p) {
  if (!SHOP) return;
  editingProduct = p; pendingImage = null; removeImage = false;
  $("#productForm").reset();
  $("#productDialogTitle").textContent = p ? "Edit product" : "Add product";
  $("#productId").value = p?.id ?? "";
  clear($("#productCategory")).append(...Object.keys(SHOP.categories).map(c => h("option", {value: c, text: c})));
  $("#productCategory").value = p?.cat ?? Object.keys(SHOP.categories)[0] ?? "";
  fillSubOptions($("#productCategory").value, p?.sub ?? "");
  $("#productName").value = p?.name ?? ""; $("#productPrice").value = p?.price ?? ""; $("#productOldPrice").value = p?.old ?? "";
  $("#productStock").value = p?.stock ?? "in"; $("#productDescription").value = p?.desc ?? "";
  ["hot", "new", "best"].forEach(t => $("#tag" + t[0].toUpperCase() + t.slice(1)).checked = (p?.tags || []).includes(t));
  setImagePreview(p?.img ? "/images/products/" + encodeURIComponent(p.img) : null);
  $("#productImageCurrent").textContent = p?.img ? "Current image: " + p.img : "JPG, PNG or WEBP, up to 5 MB. Square photos look best.";
  $("#productMsg").textContent = ""; updateSerp();
  $("#productDialog").showModal();
}
$("#newProduct").onclick = () => openProduct(null);
$("#productForm").onsubmit = async e => {
  e.preventDefault();
  const msg = $("#productMsg"); msg.textContent = "";
  const name = $("#productName").value.trim(), price = Number($("#productPrice").value), oldPrice = Number($("#productOldPrice").value);
  if (!name) { msg.textContent = "Enter a product name."; return; }
  if (!(price > 0)) { msg.textContent = "Enter a price greater than zero."; return; }
  if (oldPrice > 0 && oldPrice <= price) { msg.textContent = "The previous price should be higher than the current price (or leave it empty)."; return; }
  const oldId = $("#productId").value;
  const product = {
    id: oldId ? Number(oldId) : Math.max(0, ...SHOP.products.map(p => Number(p.id) || 0)) + 1,
    name, cat: $("#productCategory").value, sub: $("#productSubcategory").value, price,
    tags: [$("#tagHot").checked ? "hot" : null, $("#tagNew").checked ? "new" : null, $("#tagBest").checked ? "best" : null].filter(Boolean),
    stock: $("#productStock").value, desc: $("#productDescription").value.trim()
  };
  if (oldPrice > 0) product.old = oldPrice;
  if (editingProduct) for (const k of ["rating", "reviews", "sold", "free"]) if (editingProduct[k] != null) product[k] = editingProduct[k];
  if (editingProduct?.img && !removeImage) product.img = editingProduct.img;
  const btn = $("#saveProduct"); btn.disabled = true; btn.textContent = "Saving…";
  try {
    if (pendingImage) {
      const body = new FormData(); body.append("image", pendingImage);
      product.img = (await call("/api/admin/shop/image", "POST", undefined, body)).img;
    }
    const products = oldId ? SHOP.products.map(p => String(p.id) === oldId ? product : p) : [...SHOP.products, product];
    await saveCatalog({...SHOP, products});
    $("#productDialog").close(); toast(oldId ? "Product updated." : "Product added.");
  } catch (err) { msg.textContent = err.message; }
  finally { btn.disabled = false; btn.textContent = "Save product"; }
};
async function deleteProduct(p) {
  if (!await confirmBox("Delete product?", `“${p.name}” will be removed from the shop. This cannot be undone.`)) return;
  try { await saveCatalog({...SHOP, products: SHOP.products.filter(x => x.id !== p.id)}); toast("Product deleted."); }
  catch (err) { toast(err.message, "bad"); }
}

/* ----- categories ----- */
function renderCategories() {
  if (!SHOP) return;
  const grid = clear($("#categoryGrid"));
  Object.entries(SHOP.categories).forEach(([name, subs]) => {
    const count = SHOP.products.filter(p => p.cat === name).length;
    grid.append(h("article", {class: "cat-card"},
      h("header", {}, h("div", {}, h("h3", {text: name}), h("p", {class: "muted", text: `${count} product${count === 1 ? "" : "s"}`})),
        h("a", {class: "icon-btn", href: "/category/" + slug(name), target: "_blank", rel: "noopener", "aria-label": "View " + name + " on website", title: "View on website"}, icon("external"))),
      h("div", {class: "sub-chips"}, subs.length ? subs.map(s => h("span", {class: "badge", text: s})) : h("span", {class: "muted small", text: "No subcategories"})),
      h("div", {class: "cat-actions"},
        h("button", {class: "btn ghost sm", type: "button", onclick: () => openCategory(name)}, icon("edit"), "Edit"),
        h("button", {class: "btn ghost sm", type: "button", onclick: () => deleteCategory(name)}, icon("trash"), "Delete"))));
  });
}
function openCategory(name) {
  if (!SHOP) return;
  $("#categoryForm").reset();
  $("#categoryDialogTitle").textContent = name ? "Edit category" : "New category";
  $("#categoryOriginal").value = name || "";
  $("#categoryName").value = name || "";
  $("#categorySubs").value = name ? SHOP.categories[name].join(", ") : "";
  $("#categoryMsg").textContent = "";
  $("#categoryDialog").showModal();
}
$("#newCategory").onclick = () => openCategory(null);
$("#categoryForm").onsubmit = async e => {
  e.preventDefault();
  const original = $("#categoryOriginal").value, name = $("#categoryName").value.trim(), msg = $("#categoryMsg");
  const subs = [...new Set($("#categorySubs").value.split(",").map(v => v.trim()).filter(Boolean))];
  if (!name) { msg.textContent = "Enter a category name."; return; }
  if (original !== name && SHOP.categories[name]) { msg.textContent = "That category already exists."; return; }
  if (original) {
    const removed = SHOP.categories[original].filter(s => !subs.includes(s));
    const used = removed.filter(s => SHOP.products.some(p => p.cat === original && p.sub === s));
    if (used.length) { msg.textContent = `Products still use: ${used.join(", ")}. Move those products first.`; return; }
  }
  const categories = {};
  Object.entries(SHOP.categories).forEach(([key, values]) => { categories[key === original ? name : key] = key === original ? subs : values; });
  if (!original) categories[name] = subs;
  const products = SHOP.products.map(p => original && p.cat === original ? {...p, cat: name} : p);
  try { await saveCatalog({categories, products}); $("#categoryDialog").close(); toast("Category saved."); }
  catch (err) { msg.textContent = err.message; }
};
async function deleteCategory(name) {
  if (SHOP.products.some(p => p.cat === name)) { toast("Move or delete this category's products first.", "bad"); return; }
  if (Object.keys(SHOP.categories).length <= 1) { toast("At least one category is required.", "bad"); return; }
  if (!await confirmBox("Delete category?", `“${name}” and its subcategories will be removed.`)) return;
  const categories = {...SHOP.categories}; delete categories[name];
  try { await saveCatalog({categories, products: SHOP.products}); toast("Category deleted."); }
  catch (err) { toast(err.message, "bad"); }
}

/* ---------- boot ---------- */
async function boot() {
  S = await call("/api/admin/settings");
  showApp();
  fillSettings();
  route();
  await Promise.allSettled([loadCatalog(), loadRequests(), loadQuotes()]);
  renderDashboard();
}
call("/api/admin/settings").then(s => { S = s; return boot(); }).catch(() => showLogin());
