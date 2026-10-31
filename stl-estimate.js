import * as THREE from "https://unpkg.com/three@0.180.0/build/three.module.js";
import { STLLoader } from "https://unpkg.com/three@0.180.0/examples/jsm/loaders/STLLoader.js";
import { OrbitControls } from "https://unpkg.com/three@0.180.0/examples/jsm/controls/OrbitControls.js";

const MATERIALS = {
  plaPlus: {label: "PLA+"},
  absPlus: {label: "ABS+"},
  tpu: {label: "TPU"},
  petg: {label: "PETG"},
  asa: {label: "ASA"}
};
const SETTINGS = {maxFileMb: 50, maxFiles: 10, maxTotalMb: 100, maxQty: 100};
const $ = selector => document.querySelector(selector);
const elements = {
  fileInput: $("#stlFiles"), dropZone: $("#dropZone"), browse: $("#browseFiles"), clear: $("#clearFiles"),
  status: $("#estimateStatus"), viewerStatus: $("#viewerStatus"), canvas: $("#stlCanvas"),
  fileName: $("#currentFileName"), parts: $("#partList"), previous: $("#previousPart"), next: $("#nextPart"),
  resetView: $("#resetView"), removeCurrent: $("#removeCurrent"), material: $("#material"),
  units: $("#units"), quantity: $("#quantity"), dimensions: $("#dimensions"),
  volume: $("#volume"), area: $("#surfaceArea"), weight: $("#weight"), estimatedPrintTime: $("#estimatedPrintTime"),
  partRange: $("#partRange"), jobRange: $("#jobRange"), note: $("#estimateNote"), copy: $("#copyEstimate"),
  quote: $("#requestQuote"), quoteDialog: $("#quoteRequestDialog"), quoteForm: $("#estimateRequestForm"),
  quoteSummary: $("#dialogEstimateSummary"), quoteStatus: $("#quoteRequestStatus"), quoteResult: $("#quoteRequestResult"),
  sendQuote: $("#sendQuoteRequest")
};
const loader = new STLLoader();
const parts = [];
let activeIndex = -1;
let mesh = null;
let renderer = null;
let controls = null;
let scene = null;
let camera = null;
let latestEstimate = null;
let estimateError = "";
let estimateSequence = 0;
let estimateTimer = null;

function money(value) {
  return "Rs. " + Math.round(value).toLocaleString("en-LK");
}

function setStatus(message, isError = false) {
  elements.status.textContent = message;
  elements.status.classList.toggle("is-error", isError);
}

function initializeViewer() {
  try {
    scene = new THREE.Scene();
    scene.background = new THREE.Color("#f4f6f7");
    camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100000);
    camera.position.set(140, 110, 150);
    renderer = new THREE.WebGLRenderer({canvas: elements.canvas, antialias: true, alpha: false, preserveDrawingBuffer: true});
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    scene.add(new THREE.HemisphereLight(0xffffff, 0x87919b, 2));
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.2);
    keyLight.position.set(100, 160, 120);
    scene.add(keyLight);
    const fillLight = new THREE.DirectionalLight(0xffffff, 0.8);
    fillLight.position.set(-100, 50, -100);
    scene.add(fillLight);
    scene.add(new THREE.GridHelper(500, 20, 0xc3cbd1, 0xe0e4e7));
    const resize = () => {
      if (!renderer) return;
      const width = elements.canvas.clientWidth;
      const height = elements.canvas.clientHeight;
      if (!width || !height) return;
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
    };
    new ResizeObserver(resize).observe(elements.canvas);
    window.addEventListener("resize", resize);
    const animate = () => {
      requestAnimationFrame(animate);
      controls.update();
      renderer.render(scene, camera);
    };
    resize();
    animate();
  } catch (error) {
    renderer = null;
    elements.viewerStatus.textContent = "3D preview is unavailable in this browser; STL analysis can still continue.";
    console.error("Could not initialize the STL viewer", error);
  }
}

function analyzeGeometry(geometry) {
  geometry.computeBoundingBox();
  const box = geometry.boundingBox;
  const size = new THREE.Vector3();
  box.getSize(size);
  const position = geometry.getAttribute("position");
  let signedVolume = 0;
  let surfaceArea = 0;
  for (let index = 0; index + 2 < position.count; index += 3) {
    const ax = position.getX(index), ay = position.getY(index), az = position.getZ(index);
    const bx = position.getX(index + 1), by = position.getY(index + 1), bz = position.getZ(index + 1);
    const cx = position.getX(index + 2), cy = position.getY(index + 2), cz = position.getZ(index + 2);
    const abx = bx - ax, aby = by - ay, abz = bz - az;
    const acx = cx - ax, acy = cy - ay, acz = cz - az;
    const crossX = aby * acz - abz * acy;
    const crossY = abz * acx - abx * acz;
    const crossZ = abx * acy - aby * acx;
    surfaceArea += Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ) / 2;
    signedVolume += (ax * crossX + ay * crossY + az * crossZ) / 6;
  }
  return {size, volume: Math.abs(signedVolume), area: surfaceArea};
}

function clearMesh() {
  if (!mesh || !scene) return;
  scene.remove(mesh);
  mesh.geometry.dispose();
  mesh.material.dispose();
  mesh = null;
}

function fitCamera(object) {
  const bounds = new THREE.Box3().setFromObject(object);
  const size = bounds.getSize(new THREE.Vector3());
  const center = bounds.getCenter(new THREE.Vector3());
  const maxDimension = Math.max(size.x, size.y, size.z) || 1;
  const distance = maxDimension / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2))) * 1.55;
  controls.target.copy(center);
  camera.position.set(center.x + distance * 0.8, center.y + distance * 0.65, center.z + distance);
  camera.near = Math.max(0.1, maxDimension / 1000);
  camera.far = Math.max(10000, distance * 12);
  camera.updateProjectionMatrix();
  controls.update();
}

function displayPart(index) {
  if (index < 0 || index >= parts.length) return;
  activeIndex = index;
  const part = parts[index];
  elements.units.value = part.units;
  elements.fileName.textContent = part.file.name;
  clearMesh();
  if (renderer) {
    const geometry = part.geometry.clone();
    geometry.center();
    mesh = new THREE.Mesh(geometry, new THREE.MeshStandardMaterial({color: 0xe66b2e, roughness: 0.72, metalness: 0.05}));
    scene.add(mesh);
    fitCamera(mesh);
    elements.viewerStatus.textContent = "Drag to orbit. Scroll or pinch to zoom.";
  }
  renderAll();
}

function currentEstimate(part) {
  return part.estimate || null;
}

function buildSummary() {
  if (!latestEstimate) return null;
  const quantity = Math.max(1, Number.parseInt(elements.quantity.value, 10) || 1);
  const material = MATERIALS[elements.material.value];
  const lines = [
    "3D print estimate request",
    "Files: " + parts.map(part => part.file.name).join(", "),
    "Material: " + material.label,
    "Quantity per file: " + quantity,
    "Estimated total: " + money(latestEstimate.totalPriceLkr),
    "Estimated print time: " + Math.ceil(latestEstimate.estimatedMinutes) + " minutes",
    "Estimate only; final price and delivery are confirmed after review."
  ];
  return {total: latestEstimate.totalPriceLkr, quantity, text: lines.join("\n")};
}

function renderParts() {
  elements.parts.replaceChildren();
  parts.forEach((part, index) => {
    const estimate = currentEstimate(part);
    const row = document.createElement("li");
    row.className = "estimate-part" + (index === activeIndex ? " is-active" : "");
    const select = document.createElement("button");
    select.type = "button";
    select.className = "estimate-part-select";
    select.setAttribute("aria-current", index === activeIndex ? "true" : "false");
    const name = document.createElement("strong");
    name.textContent = part.file.name;
    const details = document.createElement("span");
    details.textContent = `${part.stats.size.x.toFixed(1)} x ${part.stats.size.y.toFixed(1)} x ${part.stats.size.z.toFixed(1)} ${part.units}`;
    const price = document.createElement("b");
    price.textContent = estimate
      ? `${Math.ceil(estimate.estimatedMinutes)} min · ~${estimate.estimatedWeightG.toFixed(1)} g`
      : estimateError || "Calculating estimate...";
    select.append(name, details, price);
    select.addEventListener("click", () => displayPart(index));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "estimate-remove";
    remove.setAttribute("aria-label", "Remove " + part.file.name);
    remove.textContent = "Remove";
    remove.addEventListener("click", () => removePart(index));
    row.append(select, remove);
    elements.parts.append(row);
  });
  if (!parts.length) {
    const empty = document.createElement("li");
    empty.className = "estimate-empty";
    empty.textContent = "No files added yet.";
    elements.parts.append(empty);
  }
}

function renderEstimate() {
  if (activeIndex < 0 || !parts[activeIndex]) {
    elements.dimensions.textContent = "-";
    elements.volume.textContent = "-";
    elements.area.textContent = "-";
    elements.weight.textContent = "-";
    elements.estimatedPrintTime.textContent = "-";
    elements.partRange.textContent = "Add an STL to see a per-part estimate.";
    elements.jobRange.textContent = "-";
    elements.note.textContent = "Planning estimate only. Actual material use depends on slicer settings and final review. Delivery is not included.";
    elements.copy.disabled = true;
    elements.quote.disabled = true;
    return;
  }
  const part = parts[activeIndex];
  const scale = part.units === "in" ? 25.4 : 1;
  const dimensions = part.stats.size;
  const volumeCc = part.stats.volume * scale ** 3 / 1000;
  const areaCm2 = part.stats.area * scale ** 2 / 100;
  const estimate = currentEstimate(part);
  const job = buildSummary();
  elements.dimensions.textContent = `${dimensions.x.toFixed(1)} x ${dimensions.y.toFixed(1)} x ${dimensions.z.toFixed(1)} ${part.units}`;
  elements.volume.textContent = volumeCc > 0 ? `${volumeCc.toFixed(2)} cm3` : "Open mesh; volume unavailable";
  elements.area.textContent = `${areaCm2.toFixed(2)} cm2`;
  elements.weight.textContent = estimate ? `~${estimate.estimatedWeightG.toFixed(1)} g` : "-";
  elements.estimatedPrintTime.textContent = latestEstimate ? `${Math.ceil(latestEstimate.estimatedMinutes)} min` : "-";
  elements.partRange.textContent = estimate
    ? `${Math.ceil(estimate.estimatedMinutes)} min estimated print time for this part`
    : estimateError || "Calculating estimate...";
  elements.jobRange.textContent = job ? money(job.total) : "-";
  elements.note.textContent = estimateError || "Planning estimate only. Print time is inferred from model volume and admin-set printer throughput; actual slicer results may differ. Delivery is not included.";
  elements.copy.disabled = !job;
  elements.quote.disabled = !job;
}

function renderAll() {
  renderParts();
  renderEstimate();
  elements.previous.disabled = activeIndex <= 0;
  elements.next.disabled = activeIndex < 0 || activeIndex >= parts.length - 1;
  elements.removeCurrent.disabled = activeIndex < 0;
  elements.clear.disabled = parts.length === 0;
}

function scheduleEstimate() {
  clearTimeout(estimateTimer);
  const sequence = ++estimateSequence;
  latestEstimate = null;
  estimateError = "";
  parts.forEach(part => { part.estimate = null; });
  renderAll();
  if (!parts.length) return;
  setStatus("Calculating estimate...");
  estimateTimer = setTimeout(() => calculateEstimate(sequence), 250);
}

async function calculateEstimate(sequence) {
  try {
    const response = await fetch("/api/estimate", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({
        materialKey: elements.material.value,
        styleKey: "standard",
        qty: Math.max(1, Number.parseInt(elements.quantity.value, 10) || 1),
        parts: parts.map(part => ({name: part.file.name, volume: part.stats.volume, area: part.stats.area, units: part.units}))
      })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || "Estimate service is unavailable. Restart the server and try again.");
    if (sequence !== estimateSequence) return;
    latestEstimate = result;
    result.parts.forEach((estimate, index) => { if (parts[index]) parts[index].estimate = estimate; });
    setStatus("Estimate updated. Final price is confirmed after review.");
  } catch (error) {
    if (sequence !== estimateSequence) return;
    estimateError = error.message;
    setStatus(error.message, true);
  }
  renderAll();
}

async function addFiles(fileList) {
  const incoming = Array.from(fileList || []);
  if (!incoming.length) return;
  const alreadyAdded = new Set(parts.map(part => part.signature));
  const candidates = incoming.filter(file => {
    if (!/\.stl$/i.test(file.name)) {
      setStatus(`${file.name}: only STL files are supported.`, true);
      return false;
    }
    if (file.size > SETTINGS.maxFileMb * 1024 * 1024) {
      setStatus(`${file.name}: exceeds the ${SETTINGS.maxFileMb} MB limit.`, true);
      return false;
    }
    const signature = `${file.name}:${file.size}:${file.lastModified}`;
    if (alreadyAdded.has(signature)) return false;
    alreadyAdded.add(signature);
    return true;
  });
  if (!candidates.length) return;
  if (parts.length + candidates.length > SETTINGS.maxFiles) {
    setStatus(`A maximum of ${SETTINGS.maxFiles} files can be estimated at once.`, true);
    return;
  }
  const totalBytes = parts.reduce((total, part) => total + part.file.size, 0) + candidates.reduce((total, file) => total + file.size, 0);
  if (totalBytes > SETTINGS.maxTotalMb * 1024 * 1024) {
    setStatus(`The combined file size must be under ${SETTINGS.maxTotalMb} MB.`, true);
    return;
  }
  elements.dropZone.classList.add("is-busy");
  elements.browse.disabled = true;
  let loaded = 0;
  let failed = 0;
  for (const [index, file] of candidates.entries()) {
    setStatus(`Reading ${file.name} (${index + 1} of ${candidates.length}) locally...`);
    try {
      const geometry = loader.parse(await file.arrayBuffer());
      if (!geometry.getAttribute("position") || geometry.getAttribute("position").count < 3) throw new Error("No triangle geometry found");
      const stats = analyzeGeometry(geometry);
      geometry.computeVertexNormals();
      parts.push({file, geometry, stats, units: "mm", signature: `${file.name}:${file.size}:${file.lastModified}`, estimate: null});
      loaded++;
    } catch (error) {
      failed++;
      console.error(`Could not read ${file.name}`, error);
    }
  }
  elements.dropZone.classList.remove("is-busy");
  elements.browse.disabled = false;
  if (loaded) {
    if (activeIndex < 0) activeIndex = 0;
    displayPart(activeIndex);
  }
  const message = [];
  if (loaded) message.push(`Added ${loaded} STL file${loaded === 1 ? "" : "s"}.`);
  if (failed) message.push(`${failed} file${failed === 1 ? "" : "s"} could not be read.`);
  if (!loaded && !failed) message.push("Those STL files are already in the estimate.");
  setStatus(message.join(" "), failed > 0 && loaded === 0);
  elements.fileInput.value = "";
  scheduleEstimate();
}

function removePart(index) {
  if (index < 0 || index >= parts.length) return;
  parts[index].geometry.dispose();
  parts.splice(index, 1);
  clearMesh();
  if (!parts.length) activeIndex = -1;
  else if (activeIndex > index) activeIndex--;
  else if (activeIndex >= parts.length) activeIndex = parts.length - 1;
  if (activeIndex >= 0) displayPart(activeIndex);
  else renderAll();
  scheduleEstimate();
  setStatus("File removed from this estimate.");
}

function clearFiles() {
  parts.forEach(part => part.geometry.dispose());
  parts.length = 0;
  activeIndex = -1;
  clearMesh();
  elements.fileInput.value = "";
  elements.fileName.textContent = "No STL loaded";
  elements.viewerStatus.textContent = "Add an STL file to preview it here.";
  setStatus("All files removed. Your models were only processed in this browser.");
  renderAll();
}

function populateSelect(select, options) {
  Object.entries(options).forEach(([key, value]) => {
    const option = document.createElement("option");
    option.value = key;
    option.textContent = value.label;
    select.append(option);
  });
}

populateSelect(elements.material, MATERIALS);
initializeViewer();
renderAll();

elements.browse.addEventListener("click", () => elements.fileInput.click());
elements.clear.addEventListener("click", clearFiles);
elements.fileInput.addEventListener("change", () => addFiles(elements.fileInput.files));
elements.dropZone.addEventListener("click", event => {
  if (!event.target.closest("button")) elements.fileInput.click();
});
["dragenter", "dragover"].forEach(type => elements.dropZone.addEventListener(type, event => {
  event.preventDefault();
  elements.dropZone.classList.add("is-over");
}));
["dragleave", "drop"].forEach(type => elements.dropZone.addEventListener(type, event => {
  event.preventDefault();
  elements.dropZone.classList.remove("is-over");
}));
elements.dropZone.addEventListener("drop", event => addFiles(event.dataTransfer.files));
elements.previous.addEventListener("click", () => displayPart(activeIndex - 1));
elements.next.addEventListener("click", () => displayPart(activeIndex + 1));
elements.removeCurrent.addEventListener("click", () => removePart(activeIndex));
elements.resetView.addEventListener("click", () => { if (mesh) fitCamera(mesh); });
elements.units.addEventListener("change", () => {
  if (parts[activeIndex]) parts[activeIndex].units = elements.units.value;
  scheduleEstimate();
});
[elements.material, elements.quantity].forEach(control => control.addEventListener("input", () => {
  if (control === elements.quantity) {
    const quantity = Math.min(1000, Math.max(1, Number.parseInt(control.value, 10) || 1));
    control.value = String(quantity);
  }
  scheduleEstimate();
}));
elements.copy.addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(buildSummary().text);
    setStatus("Estimate summary copied.");
  } catch {
    setStatus("Clipboard access is unavailable. Use Request final quote to send the estimate.", true);
  }
});
elements.quote.addEventListener("click", () => {
  const summary = buildSummary();
  if (!summary) return;
  elements.quoteSummary.textContent = summary.text;
  elements.quoteStatus.textContent = "";
  elements.quoteResult.hidden = true;
  elements.quoteDialog.showModal();
});
const closeQuoteDialog = () => elements.quoteDialog.close();
$("#closeQuoteRequest").addEventListener("click", closeQuoteDialog);
$("#cancelQuoteRequest").addEventListener("click", closeQuoteDialog);
elements.quoteDialog.addEventListener("click", event => {
  if (event.target === elements.quoteDialog) closeQuoteDialog();
});
elements.quoteForm.addEventListener("submit", async event => {
  event.preventDefault();
  const summary = buildSummary();
  if (!summary || !parts.length) return;
  const formData = new FormData();
  formData.append("type", "Print my model");
  formData.append("name", elements.quoteForm.elements.name.value.trim());
  formData.append("phone", elements.quoteForm.elements.phone.value.trim());
  formData.append("material", MATERIALS[elements.material.value].label);
  formData.append("colour", "Not specified");
  formData.append("details", summary.text);
  parts.forEach(part => formData.append("files", part.file, part.file.name));
  const chat = window.open("about:blank", "_blank");
  elements.sendQuote.disabled = true;
  elements.quoteStatus.textContent = "Sending your request and files…";
  try {
    const response = await fetch("/api/quote-requests", {method: "POST", body: formData});
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || "Could not submit your request. Please try again.");
    const message = `Quote request ${result.ref}\nName: ${elements.quoteForm.elements.name.value.trim()}\nWhatsApp: ${elements.quoteForm.elements.phone.value.trim()}\n${summary.text}`;
    const whatsappUrl = `https://wa.me/${WHATSAPP}?text=${encodeURIComponent(message)}`;
    if (chat) chat.location.href = whatsappUrl;
    elements.quoteForm.reset();
    elements.quoteDialog.close();
    elements.quoteResult.replaceChildren(document.createTextNode(`Request ${result.ref} saved with ${parts.length} model file${parts.length === 1 ? "" : "s"}. `));
    const link = document.createElement("a");
    link.href = whatsappUrl; link.target = "_blank"; link.rel = "noopener"; link.textContent = "Continue to WhatsApp";
    elements.quoteResult.append(link);
    elements.quoteResult.hidden = false;
    setStatus(`Quote request ${result.ref} sent to 3DShop.lk.`);
  } catch (error) {
    if (chat) chat.close();
    elements.quoteStatus.textContent = error.message;
  } finally {
    elements.sendQuote.disabled = false;
  }
});
