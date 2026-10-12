// media-delete: removes ONE file from the public bucket `nbms-media`, only if it belongs to the caller's business.
// Request:  POST { url }   (Bearer token)
// Response: { ok:true }  (deleted)  |  { ok:true, skipped:true }  (empty, foreign or not ours)  |  { ok:false, error }
// Roles:    any active staff member.
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { BUCKET, belongsTo, pathFromPublicUrl } from "../_shared/media.ts";

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Method not allowed.");

  try {
    const member = await requireMember(req);

    let url = "";
    try {
      const body = await req.json();
      url = typeof body?.url === "string" ? body.url.trim() : "";
    } catch {
      return ok({ skipped: true });
    }
    if (!url) return ok({ skipped: true });

    const path = pathFromPublicUrl(url, Deno.env.get("SUPABASE_URL") ?? "");
    if (!path || !belongsTo(path, member.businessId)) return ok({ skipped: true });

    const { error } = await admin().storage.from(BUCKET).remove([path]);
    if (error) {
      console.error("media-delete: storage error:", error.message);
      return fail(500, "Delete failed.");
    }
    return ok();
  } catch (e) {
    const response = handleError(e);
    if (response.status === 500) return fail(500, "Delete failed.");
    return response;
  }
});
