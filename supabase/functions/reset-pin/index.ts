// reset-pin (Super Admin only): sets a new shared-device PIN for a PIN-eligible staff member of the same business.
// Request:  { userId, newPin }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember, requireRole } from "../_shared/auth.ts";
import { mirrorMember } from "../_shared/firebase.ts";
import { hashPin, PepperMissingError, pinLookup, validPin } from "../_shared/pin.ts";
import { logServerAction } from "../_shared/serverAudit.ts";

const PIN_ROLES = [
  "receptionist",
  "staff",
  "waiter",
  "housekeeping",
  "bar_attendant",
  "laundry_valet",
  "maintenance_technician",
  "security_guard",
  "driver",
  "restaurant_attendant",
  "kitchen_staff",
  "gym_staff",
];

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const caller = await requireMember(req);
    requireRole(caller, ["super_admin"]);

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch (_e) {
      return fail(400, "Invalid request.");
    }
    if (!body || typeof body !== "object") return fail(400, "Invalid request.");

    const userId = typeof body.userId === "string" ? body.userId.trim() : "";
    if (!userId) return fail(400, "userId is required.");
    if (!validPin(body.newPin)) return fail(400, "PIN must be 4 to 6 digits.");
    const newPin = body.newPin;

    const db = admin();
    const target = await db
      .from("business_members")
      .select("user_id, business_id, role, name, email, phone, status")
      .eq("user_id", userId)
      .maybeSingle();
    if (target.error) throw new Error("member lookup failed");
    const t = target.data;
    if (!t || t.business_id !== caller.businessId) return fail(404, "User not found.");
    if (!PIN_ROLES.includes(t.role as string)) return fail(400, "This role must use email login.");

    const lookup = await pinLookup(caller.businessId, newPin);
    const hash = await hashPin(newPin);

    const update = await db
      .from("business_members")
      .update({
        uses_pin: true,
        pin_lookup: lookup,
        pin_hash: hash,
        pin_failed_attempts: 0,
        pin_locked_until: null,
      })
      .eq("user_id", userId)
      .eq("business_id", caller.businessId);
    if (update.error) {
      if ((update.error as { code?: string }).code === "23505") {
        return fail(409, "That PIN is already in use. Choose another.");
      }
      console.error("reset-pin: update failed:", update.error.message);
      return fail(500, "Could not reset the PIN. Please try again.");
    }

    try {
      await mirrorMember(caller.businessId, {
        userId,
        role: t.role as string,
        name: t.name as string,
        email: t.email as string | null,
        phone: t.phone as string | null,
        status: t.status as string,
        usesPin: true,
      });
    } catch (e) {
      console.error("reset-pin: mirror failed:", e instanceof Error ? e.message : "unknown");
    }

    await logServerAction(
      caller.businessId,
      caller.userId,
      caller.name,
      caller.role,
      "pin_reset",
      "users",
      userId,
      null,
      { name: t.name },
    );
    return ok();
  } catch (e) {
    if (e instanceof PepperMissingError) return fail(500, "Server is not configured.");
    return handleError(e);
  }
});
