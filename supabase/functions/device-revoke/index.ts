// device-revoke: removes a registered device. You can remove your own; a Super Admin can remove
// a device belonging to anyone in the same business.
// Request:  { registrationId, targetUserId? }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { logServerAction } from "../_shared/serverAudit.ts";

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
    const registrationId = typeof body.registrationId === "string" ? body.registrationId.trim() : "";
    const targetUserId = typeof body.targetUserId === "string" ? body.targetUserId.trim() : "";
    if (!registrationId) return fail(400, "registrationId is required.");

    const db = admin();
    const found = await db
      .from("device_registrations")
      .select("id, user_id, business_id, device_id, is_active")
      .eq("id", registrationId)
      .maybeSingle();
    if (found.error) {
      // Not a valid id format, or a lookup problem: the caller just sees "not found".
      console.error("device-revoke: lookup failed:", found.error.message);
      return fail(404, "Device not found.");
    }
    const reg = found.data;
    if (!reg || reg.business_id !== caller.businessId) return fail(404, "Device not found.");
    if (targetUserId && targetUserId !== reg.user_id) return fail(404, "Device not found.");

    const isSelf = reg.user_id === caller.userId;
    if (!isSelf && caller.role !== "super_admin") {
      return fail(403, "Only a Super Admin can remove another person's device.");
    }

    if (reg.is_active === true) {
      const upd = await db
        .from("device_registrations")
        .update({ is_active: false, revoked_at: new Date().toISOString(), revoked_by: caller.userId })
        .eq("id", reg.id);
      if (upd.error) {
        console.error("device-revoke: update failed:", upd.error.message);
        return fail(500, "Couldn't remove the device. Please try again.");
      }
      const ev = await db.from("device_auth_events").insert({
        business_id: caller.businessId,
        user_id: reg.user_id,
        device_id: reg.device_id,
        event_type: "revoke",
        method: "pin",
        detail: isSelf ? "self-service" : `revoked by ${caller.name}`,
      });
      if (ev.error) console.error("device-revoke: event log failed:", ev.error.message);

      if (!isSelf) {
        await logServerAction(
          caller.businessId,
          caller.userId,
          caller.name,
          caller.role,
          "device_lock_revoke",
          "device_registrations",
          reg.id as string,
          { isActive: true, userId: reg.user_id },
          { isActive: false },
        );
      }
    }
    return ok();
  } catch (e) {
    return handleError(e);
  }
});
