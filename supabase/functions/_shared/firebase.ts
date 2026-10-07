// Firebase helpers for Supabase Edge Functions (Deno).
// Uses only Web Crypto and fetch. No Firebase SDK.
// Secret: FIREBASE_SERVICE_ACCOUNT_JSON (service-account JSON text).
// Optional: FIREBASE_DATABASE_URL (Realtime Database address).

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

let cachedAccount: ServiceAccount | null = null;
let cachedKey: CryptoKey | null = null;
const tokenCache = new Map<string, { token: string; expiresAt: number }>();

const DEFAULT_SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/firebase.database",
  "https://www.googleapis.com/auth/userinfo.email",
];

export function serviceAccount(): ServiceAccount {
  if (cachedAccount) return cachedAccount;
  const raw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("Missing FIREBASE_SERVICE_ACCOUNT_JSON");
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(raw);
  } catch (_e) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON");
  }
  const project_id = parsed.project_id as string | undefined;
  const client_email = parsed.client_email as string | undefined;
  let private_key = parsed.private_key as string | undefined;
  if (!project_id || !client_email || !private_key) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is missing project_id, client_email or private_key");
  }
  if (!private_key.includes("\n")) private_key = private_key.replace(/\\n/g, "\n");
  cachedAccount = { project_id, client_email, private_key };
  return cachedAccount;
}

// ── encoding helpers ──
function bytesToB64url(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function strToB64url(s: string): string {
  return bytesToB64url(new TextEncoder().encode(s));
}

function pemToDer(pem: string): Uint8Array {
  const b64 = pem
    .replace(/-----BEGIN [A-Z ]+-----/g, "")
    .replace(/-----END [A-Z ]+-----/g, "")
    .replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function signingKey(): Promise<CryptoKey> {
  if (cachedKey) return cachedKey;
  const der = pemToDer(serviceAccount().private_key);
  cachedKey = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return cachedKey;
}

async function signJwt(payload: Record<string, unknown>): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const signingInput = `${strToB64url(JSON.stringify(header))}.${strToB64url(JSON.stringify(payload))}`;
  const key = await signingKey();
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${bytesToB64url(new Uint8Array(sig))}`;
}

// ── OAuth2 access token (service account, JWT-bearer) ──
export async function accessToken(scopes: string[] = DEFAULT_SCOPES): Promise<string> {
  const scope = scopes.join(" ");
  const now = Date.now();
  const cached = tokenCache.get(scope);
  if (cached && cached.expiresAt - 5 * 60 * 1000 > now) return cached.token;

  const sa = serviceAccount();
  const iat = Math.floor(now / 1000);
  const assertion = await signJwt({
    iss: sa.client_email,
    scope,
    aud: "https://oauth2.googleapis.com/token",
    iat,
    exp: iat + 3600,
  });

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }).toString(),
  });
  if (!res.ok) throw new Error(`Google token request failed (${res.status})`);
  const body = await res.json() as { access_token?: string; expires_in?: number };
  if (!body.access_token) throw new Error("Google token response had no access_token");
  tokenCache.set(scope, {
    token: body.access_token,
    expiresAt: now + (body.expires_in ?? 3600) * 1000,
  });
  return body.access_token;
}

// ── Firebase custom token ──
export async function createCustomToken(uid: string, claims: Record<string, unknown>): Promise<string> {
  if (!uid || uid.length > 128) throw new Error("Invalid uid for custom token");
  const sa = serviceAccount();
  const iat = Math.floor(Date.now() / 1000);
  return await signJwt({
    iss: sa.client_email,
    sub: sa.client_email,
    aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    iat,
    exp: iat + 3600,
    uid,
    claims: { ...claims },
  });
}

// ── Firestore value conversion ──
export function toFsValue(v: unknown): unknown {
  if (v === null || v === undefined) return { nullValue: null };
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  switch (typeof v) {
    case "string":
      return { stringValue: v };
    case "boolean":
      return { booleanValue: v };
    case "number":
      return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
    case "object": {
      if (Array.isArray(v)) return { arrayValue: { values: v.map(toFsValue) } };
      const fields: Record<string, unknown> = {};
      for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
        if (val === undefined) continue;
        fields[k] = toFsValue(val);
      }
      return { mapValue: { fields } };
    }
    default:
      return { nullValue: null };
  }
}

// deno-lint-ignore no-explicit-any
export function fromFsValue(v: any): unknown {
  if (v === null || v === undefined) return null;
  if ("nullValue" in v) return null;
  if ("stringValue" in v) return v.stringValue;
  if ("booleanValue" in v) return v.booleanValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return v.doubleValue;
  if ("timestampValue" in v) return new Date(v.timestampValue);
  if ("arrayValue" in v) return (v.arrayValue.values ?? []).map(fromFsValue);
  if ("mapValue" in v) {
    const out: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(v.mapValue.fields ?? {})) out[k] = fromFsValue(val);
    return out;
  }
  if ("referenceValue" in v) return v.referenceValue;
  if ("geoPointValue" in v) return v.geoPointValue;
  if ("bytesValue" in v) return v.bytesValue;
  return null;
}

// ── Firestore REST helpers (database "(default)") ──
function fsBase(): string {
  return `https://firestore.googleapis.com/v1/projects/${serviceAccount().project_id}/databases/(default)/documents`;
}

function fsPathUrl(path: string): string {
  const clean = path.replace(/^\/+|\/+$/g, "");
  return `${fsBase()}/${clean.split("/").map(encodeURIComponent).join("/")}`;
}

async function fsFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const token = await accessToken();
  return await fetch(url, {
    ...init,
    headers: {
      ...(init.headers ?? {}),
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
  });
}

async function fsError(action: string, res: Response): Promise<Error> {
  let detail = "";
  try {
    const body = await res.json() as { error?: { message?: string } };
    detail = body?.error?.message ? `: ${body.error.message}` : "";
  } catch (_e) {
    // ignore body parse problems
  }
  return new Error(`Firestore ${action} failed (${res.status})${detail}`);
}

function fieldPath(key: string): string {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(key) ? key : "`" + key.replace(/\\/g, "\\\\").replace(/`/g, "\\`") + "`";
}

export async function fsGet(path: string): Promise<Record<string, unknown> | null> {
  const res = await fsFetch(fsPathUrl(path), { method: "GET" });
  if (res.status === 404) return null;
  if (!res.ok) throw await fsError("get", res);
  const doc = await res.json() as { fields?: Record<string, unknown> };
  const out: Record<string, unknown> = {};
  for (const [k, val] of Object.entries(doc.fields ?? {})) out[k] = fromFsValue(val);
  return out;
}

export async function fsSet(path: string, data: Record<string, unknown>, merge = true): Promise<void> {
  const fields: Record<string, unknown> = {};
  for (const [k, val] of Object.entries(data)) {
    if (val === undefined) continue;
    fields[k] = toFsValue(val);
  }
  let url = fsPathUrl(path);
  if (merge) {
    const mask = Object.keys(fields).map((k) => `updateMask.fieldPaths=${encodeURIComponent(fieldPath(k))}`);
    if (mask.length > 0) url += `?${mask.join("&")}`;
  }
  const res = await fsFetch(url, { method: "PATCH", body: JSON.stringify({ fields }) });
  if (!res.ok) throw await fsError("set", res);
}

export async function fsDelete(path: string): Promise<void> {
  const res = await fsFetch(fsPathUrl(path), { method: "DELETE" });
  if (res.status === 404) return;
  if (!res.ok) throw await fsError("delete", res);
}

export async function fsAdd(collectionPath: string, data: Record<string, unknown>): Promise<string> {
  const fields: Record<string, unknown> = {};
  for (const [k, val] of Object.entries(data)) {
    if (val === undefined) continue;
    fields[k] = toFsValue(val);
  }
  const res = await fsFetch(fsPathUrl(collectionPath), { method: "POST", body: JSON.stringify({ fields }) });
  if (!res.ok) throw await fsError("add", res);
  const doc = await res.json() as { name?: string };
  return (doc.name ?? "").split("/").pop() ?? "";
}

// ── mirrors written for the security rules ──
export async function mirrorMember(
  businessId: string,
  m: {
    userId: string;
    role: string;
    name: string;
    email?: string | null;
    phone?: string | null;
    status: string;
    usesPin: boolean;
  },
): Promise<void> {
  await fsSet(`businesses/${businessId}/users/${m.userId}`, {
    uid: m.userId,
    role: m.role,
    name: m.name,
    email: m.email ?? null,
    phone: m.phone ?? null,
    status: m.status,
    usesPin: m.usesPin,
    updatedAt: new Date(),
  });
}

export async function mirrorBusiness(
  b: {
    id: string;
    name: string;
    code: string;
    enabledRoles: string[];
    currency: string;
    currencySymbol: string;
    timezone: string;
    businessType: string;
    maintenanceMode: boolean;
    maintenanceMessage: string | null;
  },
): Promise<void> {
  await fsSet(`businesses/${b.id}`, {
    name: b.name,
    code: b.code,
    enabledRoles: b.enabledRoles,
    currency: b.currency,
    currencySymbol: b.currencySymbol,
    timezone: b.timezone,
    businessType: b.businessType,
    maintenanceMode: b.maintenanceMode,
    maintenanceMessage: b.maintenanceMessage,
  });
}

// ── Realtime Database ──
export async function rtdbSet(path: string, value: unknown): Promise<void> {
  const base = (Deno.env.get("FIREBASE_DATABASE_URL") ?? `https://${serviceAccount().project_id}-default-rtdb.firebaseio.com`)
    .replace(/\/+$/, "");
  const clean = path.replace(/^\/+|\/+$/g, "");
  const token = await accessToken();
  const res = await fetch(`${base}/${clean}.json?access_token=${encodeURIComponent(token)}`, {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(value === undefined ? null : value),
  });
  if (!res.ok) throw new Error(`Realtime Database set failed (${res.status})`);
}
