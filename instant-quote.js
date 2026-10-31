// Instant quote page. API_BASE is "" when this website is served by the 3DShop server.
// If the website is hosted elsewhere, set it to the server address, e.g. "https://api.3dshop.lk"
const API_BASE = "";
(async () => {
  const state = {file: null, mat: null, col: null};
  let cfg = {enabled: false, maxFileMB: 50, maxQty: 100};
  const lkr = n => "Rs. " + Math.round(n).toLocaleString("en-LK");
  const api = async (path, opts) => {
    const r = await fetch(API_BASE + path, opts);
    const j = await r.json().catch(() => ({error: "Unexpected server response."}));
    if (!r.ok) throw new Error(j.error || "Something went wrong.");
    return j;
  };
  const showErr = m => { const e = $("#uploadErr"); e.textContent = m || ""; e.hidden = !m; };

  try { cfg = await api("/api/config"); } catch (e) { cfg.enabled = false; }
  $("#dropHint").textContent = `${(cfg.formats || ["stl", "obj", "3mf", "step"]).join(", ").toUpperCase()} · up to ${cfg.maxFileMB} MB`;
  if (!cfg.enabled) {
    $("#drop").classList.add("is-off");
    $("#file").disabled = true;
    showErr("Instant quotes are not available right now. Please use the manual quote form or message us on WhatsApp.");
  }
  $("#qty").max = cfg.maxQty;

  const file = $("#file"), drop = $("#drop");
  ["dragenter", "dragover"].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.add("over"); }));
  ["dragleave", "drop"].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove("over"); }));
  drop.addEventListener("drop", e => { if (cfg.enabled && e.dataTransfer.files[0]) send(e.dataTransfer.files[0]); });
  file.addEventListener("change", () => file.files[0] && send(file.files[0]));

  function send(f) {
    showErr("");
    if (f.size > cfg.maxFileMB * 1048576) return showErr(`That file is larger than ${cfg.maxFileMB} MB. Please message us on WhatsApp.`);
    const fd = new FormData(); fd.append("file", f);
    $("#progress").hidden = false; $("#progressText").textContent = "Uploading and analysing your model…"; drop.classList.add("busy");
    $("#stepOptions").hidden = true; reset();
    fetch(API_BASE + "/api/upload", {method: "POST", body: fd})
      .then(async r => { const j = await r.json().catch(() => ({})); if (!r.ok) throw new Error(j.error || "Upload failed."); return j; })
      .then(j => { state.file = j; drawOptions(); })
      .catch(e => showErr(e.message))
      .finally(() => { $("#progress").hidden = true; drop.classList.remove("busy"); file.value = ""; });
  }

  function reset() {
    $("#amount").textContent = "—"; $("#unit").textContent = ""; $("#note").textContent = "Upload a model to see your price.";
    $("#orderBtn").hidden = true; $("#ref").hidden = true;
  }

  function drawOptions() {
    const f = state.file;
    const info = $("#fileInfo"); info.textContent = "";
    const t = document.createElement("b"); t.textContent = f.fileName; info.append(t);
    const d = document.createElement("span");
    d.textContent = ` · ${f.sizeX.toFixed(1)} × ${f.sizeY.toFixed(1)} × ${f.sizeZ.toFixed(1)} mm · ${f.volumeCm3.toFixed(1)} cm³`;
    info.append(d);
    const mats = $("#mats"); mats.textContent = "";
    if (!f.materials.length) { showErr("No material is available for this model. Please message us on WhatsApp."); return; }
    f.materials.forEach(m => {
      const b = document.createElement("button");
      b.type = "button"; b.className = "iq-mat"; b.setAttribute("role", "radio"); b.dataset.id = m.id;
      const n = document.createElement("b"); n.textContent = m.name; b.append(n);
      if (m.info) { const s = document.createElement("span"); s.textContent = m.info; b.append(s); }
      b.onclick = () => pickMat(m);
      mats.append(b);
    });
    $("#stepOptions").hidden = false;
    pickMat(f.materials.find(m => m.recommended) || f.materials[0]);
    $("#stepOptions").scrollIntoView({behavior: reduced ? "auto" : "smooth", block: "nearest"});
  }

  function pickMat(m) {
    state.mat = m;
    document.querySelectorAll(".iq-mat").forEach(b => b.setAttribute("aria-checked", b.dataset.id === m.id));
    const cols = $("#cols"); cols.textContent = "";
    (m.colors || []).forEach(c => {
      const b = document.createElement("button");
      b.type = "button"; b.setAttribute("role", "radio"); b.dataset.id = c.id;
      const i = document.createElement("i");
      const hex = c.hex || "";
      i.style.setProperty("--c", /^#?[0-9a-f]{3,8}$/i.test(hex) ? (hex[0] === "#" ? hex : "#" + hex) : "#ddd");
      b.append(i, document.createTextNode(c.name));
      b.onclick = () => pickCol(c);
      cols.append(b);
    });
    if (m.colors && m.colors.length) pickCol(m.colors[0]); else { state.col = null; reset(); }
  }

  function pickCol(c) {
    state.col = c;
    document.querySelectorAll("#cols button").forEach(b => b.setAttribute("aria-checked", b.dataset.id === c.id));
    price();
  }

  let timer, seq = 0;
  const qtyEl = $("#qty");
  const clampQty = () => { let q = parseInt(qtyEl.value, 10); if (!q || q < 1) q = 1; if (q > cfg.maxQty) q = cfg.maxQty; qtyEl.value = q; return q; };
  qtyEl.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(price, 500); });
  const refreshPrice = () => { clearTimeout(timer); price(); };
  qtyEl.addEventListener("change", () => { clampQty(); refreshPrice(); });
  $("#qMinus").onclick = () => { qtyEl.value = Math.max(1, clampQty() - 1); refreshPrice(); };
  $("#qPlus").onclick = () => { qtyEl.value = Math.min(cfg.maxQty, clampQty() + 1); refreshPrice(); };

  async function price() {
    if (!state.file || !state.mat || !state.col) return;
    const qty = clampQty(), my = ++seq;
    $("#amount").textContent = "Calculating…"; $("#unit").textContent = ""; $("#orderBtn").hidden = true; $("#ref").hidden = true;
    $("#priceBox").classList.add("loading");
    try {
      const r = await api("/api/quote", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({
        fileAccessId: state.file.fileAccessId, modelAccessId: state.file.modelAccessId, fileName: state.file.fileName,
        materialAccessId: state.mat.id, materialName: state.mat.name, colorAccessId: state.col.id, colorName: state.col.name, qty})});
      if (my !== seq) return;
      $("#amount").textContent = lkr(r.totalLkr);
      $("#unit").textContent = qty > 1 ? `${lkr(r.unitLkr)} each · ${qty} pcs` : "";
      $("#note").textContent = `${r.note} Valid for ${r.validityHours} hours.`;
      $("#ref").textContent = "Quote ref: " + r.ref; $("#ref").hidden = false;
      const msg = `Hi 3DShop.lk, I would like to order a 3D print.\nQuote ref: ${r.ref}\nFile: ${state.file.fileName}\nMaterial: ${state.mat.name}\nColour: ${state.col.name}\nQuantity: ${qty}\nEstimated price: ${lkr(r.totalLkr)}\n(I will attach my model file in this chat.)`;
      const b = $("#orderBtn"); b.href = `https://wa.me/${WHATSAPP}?text=${encodeURIComponent(msg)}`; b.hidden = false;
    } catch (e) {
      if (my !== seq) return;
      $("#amount").textContent = "—"; $("#note").textContent = e.message;
    } finally { if (my === seq) $("#priceBox").classList.remove("loading"); }
  }
})();
