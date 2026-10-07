// Device PIN hashing for Device Lock (Phase 6). Web Crypto only.
// Stored form: "pbkdf2$90000$<saltHex>$<hashHex>" (PBKDF2-SHA256, 16-byte salt, 256-bit key).

const encoder = new TextEncoder();
const ITERATIONS = 90000;

/** A device PIN is 6 to 10 digits and nothing else. */
export function isValidDevicePinFormat(pin: unknown): pin is string {
  return typeof pin === "string" && /^\d{6,10}$/.test(pin);
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function fromHex(hex: string): Uint8Array | null {
  if (hex.length === 0 || hex.length % 2 !== 0 || !/^[0-9a-f]+$/i.test(hex)) return null;
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
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

export async function hashDevicePin(pin: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await derive(pin, salt, ITERATIONS);
  return `pbkdf2$${ITERATIONS}$${toHex(salt)}$${toHex(hash)}`;
}

/** Constant-time comparison. Returns false for anything malformed. */
export async function verifyDevicePin(pin: string, stored: string): Promise<boolean> {
  try {
    const parts = stored.split("$");
    if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
    const iterations = Number(parts[1]);
    if (!Number.isInteger(iterations) || iterations < 1000 || iterations > 1000000) return false;
    const salt = fromHex(parts[2]);
    const expected = fromHex(parts[3]);
    if (!salt || !expected) return false;
    const actual = await derive(pin, salt, iterations);
    if (actual.length !== expected.length) return false;
    let diff = 0;
    for (let i = 0; i < actual.length; i++) diff |= actual[i] ^ expected[i];
    return diff === 0;
  } catch (_e) {
    return false;
  }
}
