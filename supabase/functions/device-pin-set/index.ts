// device-pin-set: registers this device for the caller and saves its Device PIN (6-10 digits).
// Request:  { deviceId, deviceLabel?, pin }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { hashDevicePin, isValidDevicePinFormat } from "../_shared/deviceCrypto.ts";

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const member = await requireMember(req);

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch (_e) {
      return fail(400, "Invalid request.");
    }
    if (!body || typeof body !== "object") return fail(400, "Invalid request.");

    const deviceId = typeof body.deviceId === "string" ? body.deviceId.trim() : "";
    const pin = body.pin;
    if (!deviceId || typeof pin !== "string" || pin === "") return fail(400, "deviceId and pin are required.");
    if (deviceId.length > 100) return fail(400, "deviceId and pin are required.");
    if (!isValidDevicePinFormat(pin)) return fail(400, "PIN must be 6–10 digits.");
    const rawLabel = typeof body.deviceLabel === "string" ? body.deviceLabel.trim() : "";
    const deviceLabel = rawLabel ? rawLabel.slice(0, 100) : "This device";
    const userAgent = (req.headers.get("user-agent") ?? "").slice(0, 300) || null;

    const db = admin();

    const row = await db.from("business_members").select("uses_pin").eq("user_id", member.userId).maybeSingle();
    if (row.error) throw new Error("member lookup failed");
    if (row.data?.uses_pin === true) {
      return fail(403, "Device Lock isn't available on shared-device PIN sessions.");
    }

    const reg = await db
      .from("device_registrations")
      .upsert(
        {
          business_id: member.businessId,
          user_id: member.userId,
          device_id: deviceId,
          device_label: deviceLabel,
          user_agent: userAgent,
          is_active: true,
          revoked_at: null,
          revoked_by: null,
        },
        { onConflict: "user_id,device_id" },
      )
      .select("id")
      .single();
    if (reg.error || !reg.data) {
      console.error("device-pin-set: register failed:", reg.error?.message);
      return fail(500, "Couldn't register this device. Please try again.");
    }

    const pinHash = await hashDevicePin(pin);
    const saved = await db.from("device_pins").upsert(
      {
        business_id: member.businessId,
        user_id: member.userId,
        registration_id: reg.data.id,
        pin_hash: pinHash,
        failed_attempts: 0,
        locked_until: null,
        updated_at: new Date().toISOString(),
      },
      { onConflict: "registration_id" },
    );
    if (saved.error) {
      console.error("device-pin-set: save failed:", saved.error.message);
      return fail(500, "Couldn't save the PIN. Please try again.");
    }

    const ev = await db.from("device_auth_events").insert({
      business_id: member.businessId,
      user_id: member.userId,
      device_id: deviceId,
      event_type: "pin_set",
      method: "pin",
    });
    if (ev.error) console.error("device-pin-set: event log failed:", ev.error.message);

    return ok();
  } catch (e) {
    return handleError(e);
  }
});
