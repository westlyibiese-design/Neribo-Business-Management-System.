// register-push-token (any active member): saves or removes this phone's push (FCM) token.
// Request:  { token, deviceId?, platform?, remove?: boolean }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const caller = await requireMember(req);

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch (_e) {
      return fail(400, "Invalid request.");
    }
    if (!body || typeof body !== "object") return fail(400, "Invalid request.");

    const token = typeof body.token === "string" ? body.token.trim() : "";
    if (!token || token.length > 4096) return fail(400, "A valid token is required.");
    const deviceId = typeof body.deviceId === "string" && body.deviceId.trim() ? body.deviceId.trim().slice(0, 200) : null;
    const platform = typeof body.platform === "string" && body.platform.trim() ? body.platform.trim().slice(0, 30) : "android";

    const db = admin();

    if (body.remove === true) {
      const del = await db.from("push_tokens").delete().eq("token", token).eq("user_id", caller.userId);
      if (del.error) {
        console.error("register-push-token: delete failed:", del.error.message);
        return fail(500, "Could not update push settings. Please try again.");
      }
      return ok();
    }

    // Upsert on token: a phone that was used by someone else is re-assigned to this person and business.
    const up = await db.from("push_tokens").upsert(
      {
        token,
        user_id: caller.userId,
        business_id: caller.businessId,
        device_id: deviceId,
        platform,
        updated_at: new Date().toISOString(),
      },
      { onConflict: "token" },
    );
    if (up.error) {
      console.error("register-push-token: upsert failed:", up.error.message);
      return fail(500, "Could not update push settings. Please try again.");
    }
    return ok();
  } catch (e) {
    return handleError(e);
  }
});
