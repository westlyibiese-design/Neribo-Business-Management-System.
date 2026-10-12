// Pure helpers for the media-upload / media-delete Edge Functions (Phase 32).
// No network, no Deno APIs: everything here is unit-tested in media-upload/media.test.ts.

/** Folders a member may upload into (Westly's list, minus cms-login-background). */
export const FOLDERS: readonly string[] = [
  "rooms",
  "facilities",
  "lost-found",
  "restaurant-menu",
  "bar-menu",
  "cms-hero",
  "cms-about",
  "gallery",
  "venues",
  "gym",
  "maintenance",
  "cms-contact-hero",
  "cms-faq-hero",
  "cms-facilities-hero",
  "cms-rooms-hero",
  "cms-restaurant-hero",
  "cms-testimonials-hero",
  "cms-venue-hero",
  "cms-gym-hero",
];

export const MAX_BYTES = 8 * 1024 * 1024;

/** Allowed content type -> file extension. */
export const TYPES: Record<string, string> = {
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
  "image/gif": "gif",
  "image/avif": "avif",
};

export const BUCKET = "nbms-media";

function startsWith(bytes: Uint8Array, signature: number[], offset = 0): boolean {
  if (bytes.length < offset + signature.length) return false;
  for (let i = 0; i < signature.length; i++) {
    if (bytes[offset + i] !== signature[i]) return false;
  }
  return true;
}

function ascii(bytes: Uint8Array, offset: number, length: number): string {
  if (bytes.length < offset + length) return "";
  let s = "";
  for (let i = 0; i < length; i++) s += String.fromCharCode(bytes[offset + i]);
  return s;
}

/** Detects the real image type from the first bytes. Returns the content type, or null when it is not an allowed image. */
export function sniffType(bytes: Uint8Array): string | null {
  if (startsWith(bytes, [0xff, 0xd8, 0xff])) return "image/jpeg";
  if (startsWith(bytes, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return "image/png";
  const gif = ascii(bytes, 0, 6);
  if (gif === "GIF87a" || gif === "GIF89a") return "image/gif";
  if (ascii(bytes, 0, 4) === "RIFF" && ascii(bytes, 8, 4) === "WEBP") return "image/webp";
  if (ascii(bytes, 4, 4) === "ftyp") {
    const brand = ascii(bytes, 8, 4);
    if (brand === "avif" || brand === "avis") return "image/avif";
  }
  return null;
}

/** `{businessId}/{folder}/{uuid}.{ext}` */
export function buildPath(businessId: string, folder: string, ext: string, uuid: string): string {
  return `${businessId}/${folder}/${uuid}.${ext}`;
}

/**
 * Turns a public url of OUR bucket into the object path inside the bucket.
 * Returns null for an empty value, a foreign url, or a url that is not under the bucket's public path.
 */
export function pathFromPublicUrl(url: string, supabaseUrl: string): string | null {
  if (!url || !supabaseUrl) return null;
  const prefix = `${supabaseUrl.replace(/\/+$/, "")}/storage/v1/object/public/${BUCKET}/`;
  if (!url.startsWith(prefix)) return null;
  let rest = url.slice(prefix.length);
  const cut = rest.search(/[?#]/);
  if (cut >= 0) rest = rest.slice(0, cut);
  if (!rest) return null;
  try {
    rest = decodeURIComponent(rest);
  } catch {
    return null;
  }
  return rest || null;
}

/** True only when the path sits inside this business's folder and has no traversal or empty segments. */
export function belongsTo(path: string, businessId: string): boolean {
  if (!path || !businessId) return false;
  if (!path.startsWith(`${businessId}/`)) return false;
  const segments = path.split("/");
  if (segments.length < 2) return false;
  for (const segment of segments) {
    if (segment === "" || segment === "." || segment === "..") return false;
  }
  return true;
}
