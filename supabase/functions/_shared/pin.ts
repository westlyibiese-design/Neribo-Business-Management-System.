// PIN helpers for Edge Functions (Deno). Uses only Web Crypto.
// Secret: PIN_PEPPER (a long random string set in the Supabase dashboard; never share or change it later,
// because every stored PIN lookup value is derived from it).

const encoder = new TextEncoder();
const PBKDF2_ITERATIONS = 100000;

/** Thrown when the PIN_PEPPER secret has not been added to the Supabase project yet. */
export class PepperMissingError extends Error {
  constructor() {
    super("PIN_PEPPER is not set");
  }
}

/** A PIN is 4 to 6 digits and nothing else. */
export function validPin(p: unknown): p is string {
  return typeof p === "string" && /^\d{4,6}$/.test(p);
}

function toHex(buf: ArrayBuffer): string {
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function toB64(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function fromB64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Hex HMAC-SHA256 of `${businessId}:${pin}` keyed with PIN_PEPPER. Same input always gives the same value, so it can be looked up and kept unique per business. */
export async function pinLookup(businessId: string, pin: string): Promise<string> {
  const pepper = Deno.env.get("PIN_PEPPER");
  if (!pepper) throw new PepperMissingError();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(pepper),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, encoder.encode(`${businessId}:${pin}`));
  return toHex(sig);
}

async function derive(pin: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const base = await crypto.subtle.importKey("raw", encoder.encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations },
    base,
    256,
  );
  return new Uint8Array(bits);
}

/** "pbkdf2$100000$<saltB64>$<hashB64>" (PBKDF2-SHA256, random 16-byte salt). */
export async function hashPin(pin: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await derive(pin, salt, PBKDF2_ITERATIONS);
  return `pbkdf2$${PBKDF2_ITERATIONS}$${toB64(salt)}$${toB64(hash)}`;
}

/** Compares a typed PIN with a stored hash in constant time. Returns false for anything malformed. */
export async function checkPin(pin: string, stored: string): Promise<boolean> {
  try {
    const parts = stored.split("$");
    if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
    const iterations = Number(parts[1]);
    if (!Number.isInteger(iterations) || iterations < 1000 || iterations > 1000000) return false;
    const salt = fromB64(parts[2]);
    const expected = fromB64(parts[3]);
    const actual = await derive(pin, salt, iterations);
    if (actual.length !== expected.length) return false;
    let diff = 0;
    for (let i = 0; i < actual.length; i++) diff |= actual[i] ^ expected[i];
    return diff === 0;
  } catch (_e) {
    return false;
  }
}
