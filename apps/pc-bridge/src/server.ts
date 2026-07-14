import { execFile, spawn } from "node:child_process";
import dgram from "node:dgram";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import os from "node:os";
import { dirname, join } from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { Readable } from "node:stream";
import express, { type NextFunction, type Request, type Response } from "express";
import OBSWebSocket from "obs-websocket-js";

const execFileAsync = promisify(execFile);

const HTTP_PORT = Number(process.env.PHONE_WEBCAM_PORT ?? 47889);
const PHONE_PORT = 47888;
const DISCOVERY_PORT = 47887;
const OBS_URL = process.env.PHONE_WEBCAM_OBS_URL ?? "ws://127.0.0.1:4455";
const OBS_PASSWORD = process.env.PHONE_WEBCAM_OBS_PASSWORD ?? "";
const OBS_SCENE_NAME = "Phone Webcam";
const OBS_INPUT_NAME = "Phone Webcam Browser";
// Launch OBS with the bridge so the OBS Virtual Camera is ready without opening OBS by hand.
// Set PHONE_WEBCAM_AUTO_OBS=0 to disable, or PHONE_WEBCAM_OBS_PATH to point at obs64.exe.
const AUTO_START_OBS = (process.env.PHONE_WEBCAM_AUTO_OBS ?? "1") !== "0";
const OBS_PATH = process.env.PHONE_WEBCAM_OBS_PATH ?? "";
const SCAN_PROBE_TIMEOUT_MS = 900;
const SCAN_CONCURRENCY = 64;
const SCAN_MIN_INTERVAL_MS = 10000;

type ConnectionMode = "wifi" | "usb" | "manual";

type DiscoveredPhone = {
  id: string;
  name: string;
  baseUrl: string;
  address: string;
  port: number;
  lastSeen: number;
  pinRequired: boolean;
};

type PhoneConnection = {
  baseUrl: string;
  token: string;
  mode: ConnectionMode;
  connectedAt: number;
  lastStatus: Record<string, unknown> | null;
};

type SavedConnection = {
  deviceId: string | null;
  pin: string;
  mode: ConnectionMode;
  lastBaseUrl: string;
};

type ObsState = {
  ok: boolean;
  message: string;
  updatedAt: number;
};

const app = express();
const publicDir = join(dirname(fileURLToPath(import.meta.url)), "..", "public");
const dataDir = join(dirname(fileURLToPath(import.meta.url)), "..", "data");
const savedConnectionPath = join(dataDir, "connection.json");
const savedObsPath = join(dataDir, "obs.json");
const discovered = new Map<string, DiscoveredPhone>();

let phoneConnection: PhoneConnection | null = null;
let obsState: ObsState | null = null;
let savedConnection: SavedConnection | null = loadSavedConnection();
let savedObsPassword = loadSavedObsPassword();
let recoverBusy = false;
let scanBusy = false;
let lastScanAt = 0;
// Set when auto-reconnect reaches the phone but the saved PIN is rejected
// (app was reinstalled and regenerated its PIN). Cleared on any successful pair.
let reconnectBlocked: "pin-changed" | null = null;

class PhoneHttpError extends Error {
  constructor(
    message: string,
    readonly status: number
  ) {
    super(message);
  }
}

app.use(express.json({ limit: "1mb" }));
app.use(express.static(publicDir));

const serverStartedAt = Date.now();

app.get("/api/state", (_req, res) => {
  res.json({
    bridge: {
      startedAt: serverStartedAt,
      port: HTTP_PORT,
      obsUrl: OBS_URL,
      obsSceneName: OBS_SCENE_NAME,
      obsInputName: OBS_INPUT_NAME
    },
    phone: phoneConnection
      ? {
          baseUrl: phoneConnection.baseUrl,
          mode: phoneConnection.mode,
          connectedAt: phoneConnection.connectedAt,
          lastStatus: phoneConnection.lastStatus
        }
      : null,
    autoReconnect: savedConnection
      ? {
          deviceId: savedConnection.deviceId,
          mode: savedConnection.mode,
          lastBaseUrl: savedConnection.lastBaseUrl,
          blocked: reconnectBlocked
        }
      : null,
    obs: obsState,
    discovered: listDiscoveredPhones()
  });
});

app.get("/api/discovered", (_req, res) => {
  res.json({ phones: listDiscoveredPhones() });
});

app.post(
  "/api/scan",
  asyncHandler(async (_req, res) => {
    const found = await scanForPhones();
    res.json({ ok: true, found, phones: listDiscoveredPhones() });
  })
);

app.post(
  "/api/connect",
  asyncHandler(async (req, res) => {
    const mode = parseMode(req.body.mode);
    const pin = String(req.body.pin ?? "").trim();
    const inputHost =
      mode === "usb" ? "127.0.0.1:47888" : String(req.body.host ?? "").trim();

    if (!inputHost) {
      res.status(400).json({ ok: false, message: "Phone host is required." });
      return;
    }

    if (mode === "usb") {
      await ensureUsbForward();
    }

    const baseUrl = normalizePhoneBaseUrl(inputHost);
    const pairResponse = await fetchPhoneJson(baseUrl, "/pair", {
      method: "POST",
      body: JSON.stringify({ pin }),
      headers: { "Content-Type": "application/json" }
    });

    const token = String(pairResponse.token ?? "");
    if (!token) {
      throw new Error("Phone did not return a session token.");
    }

    phoneConnection = {
      baseUrl,
      token,
      mode,
      connectedAt: Date.now(),
      lastStatus: null
    };
    reconnectBlocked = null;

    saveConnection(pin, mode, baseUrl, pairResponse);
    await refreshPhoneStatus();
    res.json({ ok: true, phone: phoneConnection });
  })
);

app.post("/api/disconnect", (_req, res) => {
  phoneConnection = null;
  savedConnection = null;
  try {
    writeFileSync(savedConnectionPath, JSON.stringify(null));
  } catch {
    // Nothing saved yet.
  }
  res.json({ ok: true });
});

app.post(
  "/api/control",
  asyncHandler(async (req, res) => {
    const result = await withAutoRepair((phone) =>
      fetchPhoneJson(phone.baseUrl, "/control", {
        method: "POST",
        body: JSON.stringify(req.body ?? {}),
        headers: phoneHeaders(phone, { "Content-Type": "application/json" })
      })
    );
    await refreshPhoneStatus();
    res.json({ ok: true, result });
  })
);

app.post(
  "/api/usb/forward",
  asyncHandler(async (_req, res) => {
    await ensureUsbForward();
    const adbPath = await resolveAdbPath();
    const devices = adbPath ? await execFileAsync(adbPath, ["devices"]) : null;
    res.json({ ok: true, output: devices?.stdout.trim() ?? "USB forwarding is active." });
  })
);

app.post(
  "/api/obs/start",
  asyncHandler(async (req, res) => {
    const password =
      String(req.body?.password ?? "").trim() || OBS_PASSWORD || savedObsPassword;
    const result = await configureObs(password);
    saveObsPassword(password);
    obsState = {
      ok: true,
      message: result,
      updatedAt: Date.now()
    };
    res.json({ ok: true, message: result });
  })
);

app.get(
  "/proxy/snapshot.jpg",
  asyncHandler(async (_req, res) => {
    const { bytes, contentType } = await withAutoRepair(async (phone) => {
      const upstream = await fetch(withToken(phone, "/snapshot.jpg"), {
        headers: phoneHeaders(phone),
        signal: AbortSignal.timeout(5000)
      });
      if (!upstream.ok || !upstream.body) {
        throw new PhoneHttpError(
          `Phone snapshot returned HTTP ${upstream.status}.`,
          upstream.status
        );
      }
      return {
        bytes: Buffer.from(await upstream.arrayBuffer()),
        contentType: upstream.headers.get("content-type") ?? "image/jpeg"
      };
    });

    res.setHeader("Content-Type", contentType);
    res.setHeader("Cache-Control", "no-store");
    res.send(bytes);
  })
);

app.get("/proxy/audio.wav", async (_req, res) => {
  const phone = phoneConnection;
  if (!phone) {
    res.status(503).send("No phone is connected.");
    return;
  }

  try {
    const upstream = await fetch(withToken(phone, "/audio.wav"), {
      headers: phoneHeaders(phone)
    });

    if (!upstream.ok || !upstream.body) {
      res.status(502).send(`Phone audio returned HTTP ${upstream.status}.`);
      return;
    }

    res.writeHead(200, {
      "Content-Type": "audio/wav",
      "Cache-Control": "no-store",
      Connection: "close"
    });

    // No mid-stream retry: reconnecting inside one response would splice a second
    // WAV header into the stream and break the decoder. End the response when the
    // upstream dies; the player reconnects and gets a clean header.
    for await (const chunk of Readable.fromWeb(upstream.body as never)) {
      if (res.destroyed) {
        break;
      }
      res.write(chunk);
    }
  } catch {
    // Upstream died; fall through to end the response.
  }
  res.end();
});

app.get("/proxy/stream.mjpg", async (_req, res) => {
  res.writeHead(200, {
    "Content-Type": "multipart/x-mixed-replace; boundary=frame",
    "Cache-Control": "no-store, no-cache, must-revalidate",
    Pragma: "no-cache",
    Connection: "close"
  });

  while (!res.destroyed) {
    if (!phoneConnection) {
      await delay(1000);
      continue;
    }

    try {
      const phone = phoneConnection;
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5000);
      const upstream = await fetch(withToken(phone, "/stream.mjpg"), {
        headers: phoneHeaders(phone),
        signal: controller.signal
      });
      clearTimeout(timeout);

      if (!upstream.ok || !upstream.body) {
        throw new Error(`Phone stream returned HTTP ${upstream.status}.`);
      }

      for await (const chunk of Readable.fromWeb(upstream.body as never)) {
        if (res.destroyed) {
          break;
        }
        res.write(chunk);
      }
    } catch {
      await delay(1200);
    }
  }
});

app.use((error: Error, _req: Request, res: Response, _next: NextFunction) => {
  res.status(500).json({ ok: false, message: error.message });
});

app.listen(HTTP_PORT, "127.0.0.1", () => {
  console.log(`Phone Webcam Bridge running at http://127.0.0.1:${HTTP_PORT}`);
  startDiscoveryListener();
  void autoStartObs();
});

setInterval(() => {
  pruneDiscoveredPhones();
  void refreshPhoneStatus();
}, 2500).unref();

setInterval(() => {
  void autoRecoverConnection();
}, 5000).unref();

function asyncHandler(
  handler: (req: Request, res: Response, next: NextFunction) => Promise<void>
) {
  return (req: Request, res: Response, next: NextFunction) => {
    void handler(req, res, next).catch(next);
  };
}

function parseMode(input: unknown): ConnectionMode {
  if (input === "usb" || input === "wifi" || input === "manual") {
    return input;
  }
  return "manual";
}

function normalizePhoneBaseUrl(input: string) {
  let value = input.trim();
  if (!value.includes("://")) {
    value = `http://${value}`;
  }

  const url = new URL(value);
  if (!url.port) {
    url.port = String(PHONE_PORT);
  }
  url.pathname = "";
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/$/, "");
}

async function fetchPhoneJson(
  baseUrl: string,
  path: string,
  init: RequestInit = {}
): Promise<Record<string, unknown>> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    signal: init.signal ?? AbortSignal.timeout(5000)
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new PhoneHttpError(
      text || `Phone returned HTTP ${response.status}.`,
      response.status
    );
  }

  return (await response.json()) as Record<string, unknown>;
}

// Runs a phone request and, if the phone rejects our session token (it restarted
// and lost its in-memory tokens), silently re-pairs with the saved PIN and retries once.
async function withAutoRepair<T>(
  action: (phone: PhoneConnection) => Promise<T>
): Promise<T> {
  const phone = requirePhone();
  try {
    return await action(phone);
  } catch (error) {
    const status = error instanceof PhoneHttpError ? error.status : 0;
    if (status !== 401 || !savedConnection) {
      throw error;
    }
    if (!(await tryPair(phone.baseUrl, savedConnection.pin, phone.mode))) {
      throw new Error(
        "Phone session expired and re-pairing failed. If the app was reinstalled, enter its new PIN and connect again."
      );
    }
    return await action(requirePhone());
  }
}

async function refreshPhoneStatus() {
  if (!phoneConnection) {
    return;
  }

  try {
    const connection = phoneConnection;
    const status = await fetchPhoneJson(connection.baseUrl, "/status", {
      headers: phoneHeaders(connection)
    });
    connection.lastStatus = status;

    // The phone reports whether our token is still valid (it loses all tokens on
    // app restart). Dropping the connection here lets autoRecoverConnection()
    // re-pair with the saved PIN within a few seconds, no clicks needed.
    if (status.tokenValid === false && phoneConnection === connection) {
      console.warn("Phone session token is stale (app restarted); re-pairing...");
      phoneConnection = null;
    }
  } catch {
    if (phoneConnection) {
      phoneConnection.lastStatus = {
        online: false,
        updatedAt: Date.now()
      };
    }
  }
}

function requirePhone() {
  if (!phoneConnection) {
    throw new Error("No phone is connected.");
  }
  return phoneConnection;
}

function withToken(phone: PhoneConnection, path: string) {
  const url = new URL(`${phone.baseUrl}${path}`);
  url.searchParams.set("token", phone.token);
  return url.toString();
}

function phoneHeaders(
  phone: PhoneConnection,
  extra: Record<string, string> = {}
): Record<string, string> {
  return {
    Authorization: `Bearer ${phone.token}`,
    ...extra
  };
}

function loadSavedConnection(): SavedConnection | null {
  try {
    const raw = JSON.parse(readFileSync(savedConnectionPath, "utf8")) as Partial<SavedConnection> | null;
    if (!raw || typeof raw.pin !== "string" || !raw.pin) {
      return null;
    }
    return {
      deviceId: typeof raw.deviceId === "string" ? raw.deviceId : null,
      pin: raw.pin,
      mode: parseMode(raw.mode),
      lastBaseUrl: String(raw.lastBaseUrl ?? "")
    };
  } catch {
    return null;
  }
}

function saveConnection(
  pin: string,
  mode: ConnectionMode,
  baseUrl: string,
  pairResponse: Record<string, unknown>
) {
  const status = pairResponse.status as Record<string, unknown> | undefined;
  const deviceId =
    typeof status?.deviceId === "string" && status.deviceId
      ? status.deviceId
      : savedConnection?.deviceId ?? null;

  savedConnection = { deviceId, pin, mode, lastBaseUrl: baseUrl };

  try {
    mkdirSync(dataDir, { recursive: true });
    writeFileSync(savedConnectionPath, JSON.stringify(savedConnection, null, 2));
  } catch (error) {
    console.warn(`Could not persist connection: ${(error as Error).message}`);
  }
}

function loadSavedObsPassword(): string {
  try {
    const raw = JSON.parse(readFileSync(savedObsPath, "utf8")) as Record<string, unknown>;
    return typeof raw.password === "string" ? raw.password : "";
  } catch {
    return "";
  }
}

function saveObsPassword(password: string) {
  savedObsPassword = password;
  try {
    mkdirSync(dataDir, { recursive: true });
    writeFileSync(savedObsPath, JSON.stringify({ password }, null, 2));
  } catch (error) {
    console.warn(`Could not persist OBS password: ${(error as Error).message}`);
  }
}

function isPhoneOnline() {
  if (!phoneConnection) {
    return false;
  }
  // lastStatus is null right after connecting, before the first refresh.
  return phoneConnection.lastStatus === null || phoneConnection.lastStatus.online !== false;
}

async function autoRecoverConnection() {
  if (recoverBusy || !savedConnection || isPhoneOnline()) {
    return;
  }

  recoverBusy = true;
  try {
    if (await tryUsbReconnect()) {
      return;
    }
    if (await tryWifiReconnect()) {
      return;
    }

    // Discovery broadcasts may be blocked (public networks, AP isolation):
    // actively probe the local subnets for the phone, then retry.
    if (Date.now() - lastScanAt >= SCAN_MIN_INTERVAL_MS) {
      await scanForPhones();
      await tryWifiReconnect();
    }
  } catch {
    // Keep retrying on the next tick.
  } finally {
    recoverBusy = false;
  }
}

async function tryUsbReconnect(): Promise<boolean> {
  const saved = savedConnection;
  if (!saved) {
    return false;
  }

  const adbPath = await resolveAdbPath();
  if (!adbPath) {
    return false;
  }

  try {
    const devices = await execFileAsync(adbPath, ["devices"]);
    const ready = devices.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .some((line) => /^\S+\s+device$/.test(line));
    if (!ready) {
      return false;
    }

    await execFileAsync(adbPath, ["forward", `tcp:${PHONE_PORT}`, `tcp:${PHONE_PORT}`]);
    return await tryPair(`http://127.0.0.1:${PHONE_PORT}`, saved.pin, "usb");
  } catch {
    return false;
  }
}

async function tryWifiReconnect(): Promise<boolean> {
  const saved = savedConnection;
  if (!saved) {
    return false;
  }

  const phones = listDiscoveredPhones().sort((a, b) => {
    const aMatch = saved.deviceId && a.id === saved.deviceId ? 1 : 0;
    const bMatch = saved.deviceId && b.id === saved.deviceId ? 1 : 0;
    return bMatch - aMatch;
  });

  for (const phone of phones) {
    if (await tryPair(phone.baseUrl, saved.pin, "wifi")) {
      return true;
    }
  }
  return false;
}

async function tryPair(baseUrl: string, pin: string, mode: ConnectionMode): Promise<boolean> {
  try {
    const pairResponse = await fetchPhoneJson(baseUrl, "/pair", {
      method: "POST",
      body: JSON.stringify({ pin }),
      headers: { "Content-Type": "application/json" },
      signal: AbortSignal.timeout(3000)
    });

    const token = String(pairResponse.token ?? "");
    if (!token) {
      return false;
    }

    phoneConnection = {
      baseUrl,
      token,
      mode,
      connectedAt: Date.now(),
      lastStatus: null
    };
    reconnectBlocked = null;
    saveConnection(pin, mode, baseUrl, pairResponse);
    await refreshPhoneStatus();
    console.log(`Auto-reconnected over ${mode} to ${baseUrl}`);
    return true;
  } catch (error) {
    // The phone answered but rejected the saved PIN: the app was reinstalled and
    // regenerated it. Auto-reconnect cannot succeed until the user enters the new PIN.
    if (error instanceof PhoneHttpError && error.status === 401) {
      if (reconnectBlocked !== "pin-changed") {
        console.warn(
          "Auto-reconnect reached the phone but the saved PIN was rejected (app reinstalled?). Enter the new PIN in the dashboard."
        );
      }
      reconnectBlocked = "pin-changed";
    }
    return false;
  }
}

async function scanForPhones(): Promise<number> {
  if (scanBusy) {
    return 0;
  }

  scanBusy = true;
  lastScanAt = Date.now();
  try {
    const candidates = collectScanCandidates();
    await addArpCandidates(candidates);

    const queue = [...candidates];
    let found = 0;
    const workers = Array.from({ length: SCAN_CONCURRENCY }, async () => {
      while (queue.length) {
        const ip = queue.pop();
        if (!ip) {
          return;
        }
        if (await probePhone(ip)) {
          found++;
        }
      }
    });

    await Promise.all(workers);
    return found;
  } finally {
    scanBusy = false;
  }
}

function collectScanCandidates(): Set<string> {
  const candidates = new Set<string>();

  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family !== "IPv4" || address.internal || address.address.startsWith("169.254.")) {
        continue;
      }

      const base = address.address.split(".").slice(0, 3).join(".");
      for (let host = 1; host < 255; host++) {
        const ip = `${base}.${host}`;
        if (ip !== address.address) {
          candidates.add(ip);
        }
      }
    }
  }

  return candidates;
}

async function addArpCandidates(candidates: Set<string>) {
  try {
    const result = await execFileAsync("arp", ["-a"]);
    for (const match of result.stdout.matchAll(/\b(\d{1,3}(?:\.\d{1,3}){3})\b/g)) {
      const ip = match[1];
      const firstOctet = Number(ip.split(".")[0]);
      if (firstOctet > 0 && firstOctet < 224 && !ip.endsWith(".255")) {
        candidates.add(ip);
      }
    }
  } catch {
    // ARP table is a best-effort extra source.
  }
}

async function probePhone(ip: string): Promise<boolean> {
  try {
    const response = await fetch(`http://${ip}:${PHONE_PORT}/status`, {
      signal: AbortSignal.timeout(SCAN_PROBE_TIMEOUT_MS)
    });
    if (!response.ok) {
      return false;
    }

    const payload = (await response.json()) as Record<string, unknown>;
    if (
      payload.ok !== true ||
      Number(payload.port) !== PHONE_PORT ||
      typeof payload.deviceName !== "string"
    ) {
      return false;
    }

    const id = String(payload.deviceId ?? `scan:${ip}`);
    discovered.set(id, {
      id,
      name: payload.deviceName,
      baseUrl: normalizePhoneBaseUrl(`${ip}:${PHONE_PORT}`),
      address: ip,
      port: PHONE_PORT,
      lastSeen: Date.now(),
      pinRequired: payload.pinRequired !== false
    });
    return true;
  } catch {
    return false;
  }
}

async function ensureUsbForward() {
  const adbPath = await resolveAdbPath();
  if (!adbPath) {
    throw new Error(
      "ADB was not found. Install Android Platform Tools, authorize the phone, then run scripts/setup-usb.ps1."
    );
  }

  await execFileAsync(adbPath, ["start-server"]).catch(() => undefined);
  const devices = await execFileAsync(adbPath, ["devices"]);
  const lines = devices.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(1);

  if (!lines.length) {
    throw new Error(
      "No phone detected over USB. Use a data cable, enable USB debugging in Developer Options, and set the USB mode to File Transfer."
    );
  }
  if (lines.some((line) => line.endsWith("unauthorized"))) {
    throw new Error(
      "The phone is connected but not authorized. Unlock the phone and accept the 'Allow USB debugging?' prompt, then try again."
    );
  }
  if (!lines.some((line) => /^\S+\s+device$/.test(line))) {
    throw new Error(`ADB does not see a ready device:\n${lines.join("\n")}`);
  }

  await execFileAsync(adbPath, ["forward", `tcp:${PHONE_PORT}`, `tcp:${PHONE_PORT}`]);
}

async function configureObs(password: string) {
  const obs = new OBSWebSocket();
  await obs.connect(OBS_URL, password || undefined);

  try {
    const sceneList = (await obs.call("GetSceneList")) as {
      scenes?: Array<{ sceneName?: string }>;
    };

    if (!sceneList.scenes?.some((scene) => scene.sceneName === OBS_SCENE_NAME)) {
      await obs.call("CreateScene", { sceneName: OBS_SCENE_NAME });
    }

    // Match the OBS canvas so the phone feed fills the whole virtual camera.
    const video = (await obs.call("GetVideoSettings")) as {
      baseWidth?: number;
      baseHeight?: number;
    };
    const canvasWidth = video.baseWidth ?? 1280;
    const canvasHeight = video.baseHeight ?? 720;

    const inputSettings = {
      url: `http://127.0.0.1:${HTTP_PORT}/obs.html`,
      width: canvasWidth,
      height: canvasHeight,
      fps: 30,
      shutdown: false,
      restart_when_active: true,
      // The obs.html page also plays the phone microphone; rerouting sends that
      // audio into the OBS mixer instead of the page's (muted) default output.
      reroute_audio: true
    };

    const inputs = (await obs.call("GetInputList")) as {
      inputs?: Array<{ inputName?: string }>;
    };

    if (inputs.inputs?.some((input) => input.inputName === OBS_INPUT_NAME)) {
      await obs.call("SetInputSettings", {
        inputName: OBS_INPUT_NAME,
        inputSettings,
        overlay: false
      });
    } else {
      await obs.call("CreateInput", {
        sceneName: OBS_SCENE_NAME,
        inputName: OBS_INPUT_NAME,
        inputKind: "browser_source",
        inputSettings,
        sceneItemEnabled: true
      });
    }

    // Monitor-only: the phone audio goes to the OBS monitoring device (set it to
    // "CABLE Input" in OBS Settings > Audio > Advanced once VB-CABLE is installed,
    // then pick "CABLE Output" as the microphone in Meet/Zoom).
    try {
      await obs.call("SetInputAudioMonitorType", {
        inputName: OBS_INPUT_NAME,
        monitorType: "OBS_MONITORING_TYPE_MONITOR_ONLY"
      });
    } catch {
      // Older obs-websocket without this call: audio still reaches the mixer.
    }

    const sceneItem = (await obs.call("GetSceneItemId", {
      sceneName: OBS_SCENE_NAME,
      sourceName: OBS_INPUT_NAME
    })) as { sceneItemId?: number };

    if (typeof sceneItem.sceneItemId === "number") {
      await obs.call("SetSceneItemTransform", {
        sceneName: OBS_SCENE_NAME,
        sceneItemId: sceneItem.sceneItemId,
        sceneItemTransform: {
          positionX: 0,
          positionY: 0,
          boundsType: "OBS_BOUNDS_STRETCH",
          boundsAlignment: 0,
          boundsWidth: canvasWidth,
          boundsHeight: canvasHeight
        }
      });
    }

    await obs.call("SetCurrentProgramScene", { sceneName: OBS_SCENE_NAME });

    try {
      const virtualCamStatus = (await obs.call("GetVirtualCamStatus")) as {
        outputActive?: boolean;
      };
      if (!virtualCamStatus.outputActive) {
        await obs.call("StartVirtualCam");
      }
    } catch {
      await obs.call("StartVirtualCam");
    }
  } finally {
    await obs.disconnect();
  }

  return `OBS scene "${OBS_SCENE_NAME}" is ready and Virtual Camera is started.`;
}

function startDiscoveryListener() {
  const socket = dgram.createSocket("udp4");

  socket.on("error", (error) => {
    console.warn(`Discovery listener stopped: ${error.message}`);
    socket.close();
  });

  socket.on("message", (message, remote) => {
    try {
      const payload = JSON.parse(message.toString("utf8")) as Record<string, unknown>;
      if (payload.type !== "jq-phone-webcam") {
        return;
      }

      const port = Number(payload.port ?? PHONE_PORT);
      const id = String(payload.id ?? `${remote.address}:${port}`);
      const baseUrl = normalizePhoneBaseUrl(
        String(payload.baseUrl ?? `${remote.address}:${port}`)
      );

      discovered.set(id, {
        id,
        name: String(payload.deviceName ?? "Android Phone"),
        baseUrl,
        address: remote.address,
        port,
        lastSeen: Date.now(),
        pinRequired: payload.pinRequired !== false
      });
    } catch {
      // Ignore packets from other local services.
    }
  });

  socket.bind(DISCOVERY_PORT, () => {
    socket.setBroadcast(true);
    console.log(`Listening for phone discovery on UDP ${DISCOVERY_PORT}`);
  });
}

function listDiscoveredPhones() {
  return [...discovered.values()].sort((a, b) => b.lastSeen - a.lastSeen);
}

function pruneDiscoveredPhones() {
  const cutoff = Date.now() - 15000;
  for (const [id, phone] of discovered) {
    if (phone.lastSeen < cutoff) {
      discovered.delete(id);
    }
  }
}

async function resolveAdbPath() {
  try {
    const command = process.platform === "win32" ? "where" : "which";
    const result = await execFileAsync(command, ["adb"]);
    const found = result.stdout.split(/\r?\n/).find(Boolean)?.trim();
    if (found) {
      return found;
    }
  } catch {
    // Fall through to the known install locations.
  }

  const candidates = [
    join(process.env.LOCALAPPDATA ?? "", "Android", "Sdk", "platform-tools", "adb.exe"),
    join(process.env.ProgramFiles ?? "", "Android", "Android Studio", "jbr", "bin", "adb.exe")
  ];
  return candidates.find((candidate) => existsSync(candidate)) ?? null;
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function resolveObsPath() {
  if (OBS_PATH && existsSync(OBS_PATH)) {
    return OBS_PATH;
  }
  const candidates = [
    join(process.env.ProgramFiles ?? "", "obs-studio", "bin", "64bit", "obs64.exe"),
    join(process.env["ProgramFiles(x86)"] ?? "", "obs-studio", "bin", "64bit", "obs64.exe")
  ];
  return candidates.find((candidate) => existsSync(candidate)) ?? null;
}

async function isObsRunning() {
  if (process.platform !== "win32") {
    return false;
  }
  try {
    const result = await execFileAsync("tasklist", [
      "/FI",
      "IMAGENAME eq obs64.exe",
      "/NH"
    ]);
    return /obs64\.exe/i.test(result.stdout);
  } catch {
    return false;
  }
}

async function ensureObsRunning() {
  if (await isObsRunning()) {
    return true;
  }
  const obsPath = await resolveObsPath();
  if (!obsPath) {
    console.warn(
      "Auto-start OBS: obs64.exe not found. Set PHONE_WEBCAM_OBS_PATH to enable auto-launch."
    );
    return false;
  }
  // OBS must run from its own bin directory or it fails to load its data files.
  const child = spawn(obsPath, ["--minimize-to-tray", "--disable-shutdown-check"], {
    cwd: dirname(obsPath),
    detached: true,
    stdio: "ignore"
  });
  child.unref();
  console.log("Auto-start OBS: launched OBS Studio.");
  return true;
}

// Called once at startup: make sure OBS is running, then keep trying to configure the
// scene + start the Virtual Camera until OBS has finished booting its WebSocket server.
async function autoStartObs() {
  if (!AUTO_START_OBS || process.platform !== "win32") {
    return;
  }
  const launched = await ensureObsRunning();
  if (!launched) {
    return;
  }
  const password = OBS_PASSWORD || savedObsPassword;
  const deadline = Date.now() + 40000;
  while (Date.now() < deadline) {
    try {
      const message = await configureObs(password);
      obsState = { ok: true, message, updatedAt: Date.now() };
      console.log(`Auto-start OBS: ${message}`);
      return;
    } catch {
      await delay(2500);
    }
  }
  console.warn(
    'Auto-start OBS: OBS was not ready in time. Click "Start OBS Camera" once it has loaded.'
  );
}
