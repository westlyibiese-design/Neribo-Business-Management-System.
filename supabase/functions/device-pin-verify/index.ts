// device-pin-verify: checks the Device PIN typed on the lock screen.
// Request:  { deviceId, pin }
// Response: { ok:true }  or  { ok:false, error }   (401 wrong PIN, 404 not registered, 423 temporarily locked)
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { isValidDevicePinFormat, verifyDevicePin } from "../_shared/deviceCrypto.ts";

const MAX_ATTEMPTS = 5;
const LOCK_MINUTES = 15;

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

    const db = admin();
    const log = async (eventType: string, detail?: string) => {
      const r = await db.from("device_auth_events").insert({
        business_id: member.businessId,
        user_id: member.userId,
        device_id: deviceId,
        event_type: eventType,
        method: "pin",
        detail: detail ?? null,
      });
      if (r.error) console.error("device-pin-verify: event log failed:", r.error.message);
    };

    const reg = await db
      .from("device_registrations")
      .select("id")
      .eq("user_id", member.userId)
      .eq("device_id", deviceId)
      .eq("is_active", true)
      .maybeSingle();
    if (reg.error) throw new Error("registration lookup failed");
    if (!reg.data) return fail(404, "This device isn't registered for Device Lock.");

    const pinRow = await db
      .from("device_pins")
      .select("id, pin_hash, failed_attempts, locked_until")
      .eq("registration_id", reg.data.id)
      .maybeSingle();
    if (pinRow.error) throw new Error("pin lookup failed");
    if (!pinRow.data) return fail(404, "No PIN set on this device.");
    const p = pinRow.data;

    if (p.locked_until) {
      const until = new Date(p.locked_until as string).getTime();
      const now = Date.now();
      if (until > now) {
        const minutes = Math.max(1, Math.ceil((until - now) / 60000));
        return fail(
          423,
          `Too many attempts. Try again in ${minutes} ${minutes === 1 ? "minute" : "minutes"}, or sign in again.`,
        );
      }
    }

    // A PIN that cannot possibly match is simply wrong; it does not count as an attempt.
    if (!isValidDevicePinFormat(pin)) return fail(401, "Incorrect PIN.");

    const correct = await verifyDevicePin(pin, p.pin_hash as string);
    if (!correct) {
      const attempts = ((p.failed_attempts as number) ?? 0) + 1;
      if (attempts >= MAX_ATTEMPTS) {
        const lockedUntil = new Date(Date.now() + LOCK_MINUTES * 60000).toISOString();
        await db
          .from("device_pins")
          .update({ failed_attempts: 0, locked_until: lockedUntil, updated_at: new Date().toISOString() })
          .eq("id", p.id);
        await log("unlock_fail", `locked for ${LOCK_MINUTES} minutes`);
        return fail(401, "Too many attempts. Locked for 15 minutes.");
      }
      await db
        .from("device_pins")
        .update({ failed_attempts: attempts, updated_at: new Date().toISOString() })
        .eq("id", p.id);
      await log("unlock_fail", `attempt ${attempts} of ${MAX_ATTEMPTS}`);
      return fail(401, "Incorrect PIN.");
    }

    await db
      .from("device_pins")
      .update({ failed_attempts: 0, locked_until: null, updated_at: new Date().toISOString() })
      .eq("id", p.id);
    await db.from("device_registrations").update({ last_used_at: new Date().toISOString() }).eq("id", reg.data.id);
    await log("unlock_success");
    return ok();
  } catch (e) {
    return handleError(e);
  }
});
