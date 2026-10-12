// media-upload: stores one image in the public bucket `nbms-media` for the caller's own business.
// Request:  POST { folder, fileName, contentType, base64 }   (base64 has no "data:" prefix; Bearer token)
// Response: { ok:true, url }  or  { ok:false, error }
// Roles:    any active staff member. The business id comes only from the member record.
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { BUCKET, buildPath, FOLDERS, MAX_BYTES, sniffType, TYPES } from "../_shared/media.ts";

const MSG_FOLDER = "Invalid upload folder.";
const MSG_TYPE = "Please choose a JPEG, PNG, WEBP, GIF, or AVIF image.";
const MSG_NO_DATA = "No file data provided.";
const MSG_UNREADABLE = "Couldn't read the uploaded file.";
const MSG_TOO_BIG = "That image is larger than 8MB. Please choose a smaller file.";
const MSG_NOT_IMAGE = "That file isn't a valid image.";
const MSG_FAILED = "Upload failed. Please try again.";

// 8 MiB of data is at most this many base64 characters (4 characters per 3 bytes, plus padding).
const MAX_BASE64_LENGTH = Math.ceil(MAX_BYTES / 3) * 4 + 4;

function decodeBase64(text: string): Uint8Array | null {
  try {
    const binary = atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Method not allowed.");

  try {
    const member = await requireMember(req);

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch {
      return fail(400, MSG_NO_DATA);
    }
    const folder = typeof body.folder === "string" ? body.folder : "";
    const contentType = typeof body.contentType === "string" ? body.contentType.toLowerCase().trim() : "";
    const base64 = typeof body.base64 === "string" ? body.base64 : "";

    if (!FOLDERS.includes(folder)) return fail(400, MSG_FOLDER);
    const ext = TYPES[contentType];
    if (!ext) return fail(400, MSG_TYPE);
    if (!base64) return fail(400, MSG_NO_DATA);
    if (base64.length > MAX_BASE64_LENGTH) return fail(400, MSG_TOO_BIG);

    const bytes = decodeBase64(base64);
    if (!bytes || bytes.length === 0) return fail(400, MSG_UNREADABLE);
    if (bytes.length > MAX_BYTES) return fail(400, MSG_TOO_BIG);
    if (sniffType(bytes) !== contentType) return fail(400, MSG_NOT_IMAGE);

    const path = buildPath(member.businessId, folder, ext, crypto.randomUUID());
    const storage = admin().storage.from(BUCKET);
    const { error } = await storage.upload(path, bytes, {
      contentType,
      cacheControl: "31536000",
      upsert: false,
    });
    if (error) {
      console.error("media-upload: storage error:", error.message);
      return fail(500, MSG_FAILED);
    }
    const { data } = storage.getPublicUrl(path);
    return ok({ url: data.publicUrl });
  } catch (e) {
    const response = handleError(e);
    // handleError answers unexpected errors with a generic message; keep the upload wording the app expects.
    if (response.status === 500) return fail(500, MSG_FAILED);
    return response;
  }
});
