const statusLine = document.getElementById("statusLine");
const discoveredList = document.getElementById("discoveredList");
const hostInput = document.getElementById("hostInput");
const pinInput = document.getElementById("pinInput");
const previewImage = document.getElementById("previewImage");
const previewFrame = document.querySelector(".preview-frame");
const previewOverlay = document.getElementById("previewOverlay");
const logBox = document.getElementById("logBox");
const profileSelect = document.getElementById("profileSelect");
const cameraSelect = document.getElementById("cameraSelect");
const mirrorInput = document.getElementById("mirrorInput");
const torchInput = document.getElementById("torchInput");
const micInput = document.getElementById("micInput");
const obsPasswordInput = document.getElementById("obsPasswordInput");
const zoomInput = document.getElementById("zoomInput");
const zoomValue = document.getElementById("zoomValue");
const exposureInput = document.getElementById("exposureInput");
const exposureValue = document.getElementById("exposureValue");
const healthDot = document.getElementById("healthDot");
const healthText = document.getElementById("healthText");

let selectedBaseUrl = "";
let connected = false;
let sliderDebounce = null;
let sliderActiveUntil = 0;

document.getElementById("refreshButton").addEventListener("click", refreshState);
document.getElementById("disconnectButton").addEventListener("click", disconnect);
document.getElementById("wifiConnectButton").addEventListener("click", () => connect("wifi"));
document.getElementById("usbConnectButton").addEventListener("click", () => connect("usb"));
document.getElementById("scanButton").addEventListener("click", scanNetwork);
document.getElementById("obsStartButton").addEventListener("click", startObs);

profileSelect.addEventListener("change", () => sendControl({ profileId: profileSelect.value }));
cameraSelect.addEventListener("change", () => sendControl({ camera: cameraSelect.value }));
mirrorInput.addEventListener("change", () => sendControl({ mirror: mirrorInput.checked }));
torchInput.addEventListener("change", () => sendControl({ torch: torchInput.checked }));
micInput.addEventListener("change", () => sendControl({ mic: micInput.checked }));

// Sliders fire rapidly while dragging: debounce the control POST and keep the state
// poll from snapping the thumb back mid-drag (sliderActiveUntil window).
function sliderControl(payload) {
  sliderActiveUntil = Date.now() + 1500;
  clearTimeout(sliderDebounce);
  sliderDebounce = setTimeout(() => sendControl(payload), 150);
}
zoomInput.addEventListener("input", () => {
  zoomValue.textContent = `${Number(zoomInput.value).toFixed(2)}x`;
  sliderControl({ zoomRatio: Number(zoomInput.value) });
});
exposureInput.addEventListener("input", () => {
  exposureValue.textContent = exposureInput.value;
  sliderControl({ exposureIndex: Number(exposureInput.value) });
});

previewImage.addEventListener("error", () => {
  previewFrame.classList.remove("connected");
  setTimeout(reloadPreview, 1200);
});

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers ?? {})
    },
    ...options
  });

  const text = await response.text();
  const data = text ? JSON.parse(text) : {};

  if (!response.ok || data.ok === false) {
    throw new Error(data.message || `Request failed: ${response.status}`);
  }

  return data;
}

async function refreshState() {
  try {
    const state = await api("/api/state");
    connected = Boolean(state.phone);
    renderDiscovered(state.discovered ?? []);
    renderStatus(state);
    syncControls(state.phone?.lastStatus);
    renderHealth(state.phone?.lastStatus);
  } catch (error) {
    setStatus(error.message, true);
  }
}

function renderDiscovered(phones) {
  discoveredList.innerHTML = "";

  if (!phones.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "No phones discovered yet.";
    discoveredList.append(empty);
    return;
  }

  for (const phone of phones) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `device-button ${selectedBaseUrl === phone.baseUrl ? "active" : ""}`;
    button.innerHTML = `<span>${escapeHtml(phone.name)}</span><small>${escapeHtml(phone.address)}</small>`;
    button.addEventListener("click", () => {
      selectedBaseUrl = phone.baseUrl;
      hostInput.value = phone.baseUrl;
      renderDiscovered(phones);
    });
    discoveredList.append(button);
  }
}

function renderStatus(state) {
  const status = state.phone?.lastStatus;
  const online = Boolean(status?.online);
  previewFrame.classList.toggle("connected", online);

  const pinChanged = state.autoReconnect?.blocked === "pin-changed";
  const searching = !state.phone && state.autoReconnect && !pinChanged;
  previewOverlay.textContent = state.phone
    ? "Connecting to phone"
    : pinChanged
      ? "Phone PIN changed - enter the new PIN and connect"
      : searching
        ? "Searching for your phone..."
        : "Waiting for phone";

  if (state.phone) {
    statusLine.textContent = `${state.phone.mode.toUpperCase()} connected to ${state.phone.baseUrl}`;
    if (!previewImage.src.includes("/proxy/stream.mjpg")) {
      reloadPreview();
    }
  } else if (pinChanged) {
    statusLine.textContent =
      "Phone PIN changed (app reinstalled?). Enter the PIN shown on the phone and press Connect.";
    previewImage.removeAttribute("src");
  } else {
    statusLine.textContent = searching
      ? `Bridge ready on port ${state.bridge.port}. Auto-reconnect is looking for your phone (USB and Wi-Fi).`
      : `Bridge ready on port ${state.bridge.port}`;
    previewImage.removeAttribute("src");
  }
}

function syncControls(status) {
  if (!status?.settings) {
    return;
  }

  const settings = status.settings;
  profileSelect.value = settings.profileId ?? profileSelect.value;
  cameraSelect.value = settings.camera ?? cameraSelect.value;
  mirrorInput.checked = Boolean(settings.mirror);
  torchInput.checked = Boolean(settings.torch);
  micInput.checked = Boolean(settings.mic);
  syncSliders(status.cameraState);
}

function syncSliders(cameraState) {
  if (!cameraState) {
    zoomInput.disabled = true;
    exposureInput.disabled = true;
    zoomValue.textContent = "";
    exposureValue.textContent = "";
    return;
  }

  zoomInput.disabled = false;
  zoomInput.max = String(cameraState.maxZoomRatio);
  exposureInput.disabled = cameraState.minExposureIndex === cameraState.maxExposureIndex;
  exposureInput.min = String(cameraState.minExposureIndex);
  exposureInput.max = String(cameraState.maxExposureIndex);

  // Don't fight the user's drag with polled values.
  if (Date.now() > sliderActiveUntil) {
    zoomInput.value = String(cameraState.zoomRatio);
    exposureInput.value = String(cameraState.exposureIndex);
    zoomValue.textContent = `${Number(cameraState.zoomRatio).toFixed(2)}x`;
    exposureValue.textContent = String(cameraState.exposureIndex);
  }
}

function renderHealth(status) {
  const actual = Number(status?.actualFps ?? 0);
  const target = Number(status?.settings?.fps ?? 0);
  const width = status?.settings?.width ?? 0;
  const height = status?.settings?.height ?? 0;

  if (!connected || !target) {
    healthDot.dataset.level = "off";
    healthText.textContent = "No stream";
    return;
  }

  const ratio = actual / target;
  healthDot.dataset.level = ratio >= 0.8 ? "good" : ratio >= 0.4 ? "warn" : "bad";
  healthText.textContent = `${actual.toFixed(1)} fps of ${target} · ${width}x${height}`;
}

async function connect(mode) {
  try {
    const host = mode === "usb" ? "127.0.0.1:47888" : hostInput.value.trim() || selectedBaseUrl;
    const pin = pinInput.value.trim();
    if (!host) {
      throw new Error("Select a discovered phone or enter its host.");
    }

    const result = await api("/api/connect", {
      method: "POST",
      body: JSON.stringify({ mode, host, pin })
    });

    selectedBaseUrl = result.phone.baseUrl;
    log(`Connected: ${result.phone.baseUrl}`);
    reloadPreview();
    await refreshState();
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function disconnect() {
  try {
    await api("/api/disconnect", { method: "POST", body: "{}" });
    log("Disconnected.");
    await refreshState();
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function scanNetwork() {
  try {
    setStatus("Scanning the local network for phones...");
    const result = await api("/api/scan", { method: "POST", body: "{}" });
    log(`Scan finished: ${result.found} phone(s) responded.`);
    await refreshState();
    if (!result.found) {
      setStatus("No phone found. Check both devices share a network, or use USB.", true);
    }
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function sendControl(payload) {
  if (!connected) {
    return;
  }

  try {
    await api("/api/control", {
      method: "POST",
      body: JSON.stringify(payload)
    });
    await refreshState();
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function startObs() {
  try {
    const result = await api("/api/obs/start", {
      method: "POST",
      body: JSON.stringify({ password: obsPasswordInput.value })
    });
    log(result.message);
  } catch (error) {
    log(`OBS manual source: http://127.0.0.1:47889/obs.html`);
    setStatus(error.message, true);
  }
}

function reloadPreview() {
  previewImage.src = `/proxy/stream.mjpg?reload=${Date.now()}`;
}

function setStatus(message, isError = false) {
  statusLine.textContent = message;
  statusLine.style.color = isError ? "var(--danger)" : "var(--muted)";
  log(message);
}

function log(message) {
  const stamp = new Date().toLocaleTimeString();
  logBox.textContent = `[${stamp}] ${message}\n${logBox.textContent}`.trim();
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

setInterval(refreshState, 1800);
refreshState();
