// device-list: the caller's active registered devices.
// Request:  POST {} (Bearer token)
// Response: { ok:true, devices:[{ registrationId, deviceId, deviceLabel, userAgent, createdAt, lastUsedAt, hasPin }] }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";

function iso(v: unknown): string | null {
  if (typeof v !== "string" || v === "") return null;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const member = await requireMember(req);
    const db = admin();
    const { data, error } = await db
      .from("device_registrations")
      .select("id, device_id, device_label, user_agent, created_at, last_used_at, device_pins(id)")
      .eq("user_id", member.userId)
      .eq("business_id", member.businessId)
      .eq("is_active", true)
      .order("created_at", { ascending: false });
    if (error) {
      console.error("device-list:", error.message);
      return fail(500, `Couldn't load your registered devices: ${error.message}`);
    }

    const devices = (data ?? []).map((r: Record<string, unknown>) => {
      const pins = r.device_pins;
      const hasPin = Array.isArray(pins) ? pins.length > 0 : pins != null;
      return {
        registrationId: r.id,
        deviceId: r.device_id,
        deviceLabel: r.device_label,
        userAgent: r.user_agent ?? null,
        createdAt: iso(r.created_at),
        lastUsedAt: iso(r.last_used_at),
        hasPin,
      };
    });
    return ok({ devices });
  } catch (e) {
    return handleError(e);
  }
});
