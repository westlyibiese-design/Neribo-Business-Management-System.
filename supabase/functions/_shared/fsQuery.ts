// Firestore REST helpers for server jobs (Phase 24). Paths are always under businesses/{bid}/.
// Reuses the token and value conversion helpers of _shared/firebase.ts.
import { accessToken, fromFsValue, serviceAccount, toFsValue } from "./firebase.ts";

export type Filter = { field: string; op: "==" | "in" | "!="; value: unknown };

/** Pass this as a value to mean "now" (a Date taken at call time). */
export const SERVER_NOW = Symbol("serverNow");

function base(): string {
  return `https://firestore.googleapis.com/v1/projects/${serviceAccount().project_id}/databases/(default)/documents`;
}

function enc(path: string): string {
  return path.replace(/^\/+|\/+$/g, "").split("/").map(encodeURIComponent).join("/");
}

function fieldPath(key: string): string {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(key) ? key : "`" + key.replace(/\\/g, "\\\\").replace(/`/g, "\\`") + "`";
}

function resolveValue(v: unknown): unknown {
  return v === SERVER_NOW ? new Date() : v;
}

function toFields(data: Record<string, unknown>): Record<string, unknown> {
  const fields: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) {
    if (v === undefined) continue;
    fields[k] = toFsValue(resolveValue(v));
  }
  return fields;
}

async function call(url: string, init: RequestInit): Promise<Response> {
  const token = await accessToken();
  return await fetch(url, {
    ...init,
    headers: { ...(init.headers ?? {}), authorization: `Bearer ${token}`, "content-type": "application/json" },
  });
}

async function describe(action: string, res: Response): Promise<Error> {
  let detail = "";
  try {
    const body = await res.json() as { error?: { message?: string } } | Array<{ error?: { message?: string } }>;
    const first = Array.isArray(body) ? body[0] : body;
    detail = first?.error?.message ? `: ${first.error.message}` : "";
  } catch (_e) {
    // ignore body parse problems
  }
  return new Error(`Firestore ${action} failed (${res.status})${detail}`);
}

const OPS: Record<Filter["op"], string> = { "==": "EQUAL", "in": "IN", "!=": "NOT_EQUAL" };

/** Builds the structuredQuery body (exported so it can be tested without the network). */
export function buildStructuredQuery(collection: string, filters: Filter[], limit: number): Record<string, unknown> {
  const fieldFilters = filters.map((f) => ({
    fieldFilter: {
      field: { fieldPath: fieldPath(f.field) },
      op: OPS[f.op],
      value: f.op === "in"
        ? { arrayValue: { values: (Array.isArray(f.value) ? f.value : [f.value]).map((x) => toFsValue(resolveValue(x))) } }
        : toFsValue(resolveValue(f.value)),
    },
  }));
  const structuredQuery: Record<string, unknown> = { from: [{ collectionId: collection }], limit };
  if (fieldFilters.length === 1) structuredQuery.where = fieldFilters[0];
  else if (fieldFilters.length > 1) structuredQuery.where = { compositeFilter: { op: "AND", filters: fieldFilters } };
  return structuredQuery;
}

export async function fsQuery(
  bid: string,
  collection: string,
  filters: Filter[],
  limit = 500,
): Promise<Array<{ id: string; data: Record<string, unknown> }>> {
  const res = await call(`${base()}/${enc(`businesses/${bid}`)}:runQuery`, {
    method: "POST",
    body: JSON.stringify({ structuredQuery: buildStructuredQuery(collection, filters, limit) }),
  });
  if (!res.ok) throw await describe(`query ${collection}`, res);
  const rows = await res.json() as Array<{ document?: { name: string; fields?: Record<string, unknown> } }>;
  const out: Array<{ id: string; data: Record<string, unknown> }> = [];
  for (const row of rows ?? []) {
    const doc = row.document;
    if (!doc) continue; // rows without a document carry only a read time
    const data: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(doc.fields ?? {})) data[k] = fromFsValue(v);
    out.push({ id: doc.name.split("/").pop() ?? "", data });
  }
  return out;
}

/** Creates the document with this id. Returns false when it already exists (HTTP 409), so a job can run twice safely. */
export async function fsCreate(
  bid: string,
  collection: string,
  id: string,
  data: Record<string, unknown>,
): Promise<boolean> {
  const url = `${base()}/${enc(`businesses/${bid}/${collection}`)}?documentId=${encodeURIComponent(id)}`;
  const res = await call(url, { method: "POST", body: JSON.stringify({ fields: toFields(data) }) });
  if (res.status === 409) return false;
  if (!res.ok) throw await describe(`create ${collection}`, res);
  return true;
}

/** Changes exactly these fields of an existing document (other fields are untouched). A missing document is skipped quietly. */
export async function fsUpdateFields(bid: string, path: string, fields: Record<string, unknown>): Promise<void> {
  const keys = Object.keys(fields).filter((k) => fields[k] !== undefined);
  if (keys.length === 0) return;
  const mask = keys.map((k) => `updateMask.fieldPaths=${encodeURIComponent(fieldPath(k))}`).join("&");
  const url = `${base()}/${enc(`businesses/${bid}/${path}`)}?${mask}&currentDocument.exists=true`;
  const res = await call(url, { method: "PATCH", body: JSON.stringify({ fields: toFields(fields) }) });
  if (res.status === 404) return;
  if (!res.ok) throw await describe(`update ${path}`, res);
}
