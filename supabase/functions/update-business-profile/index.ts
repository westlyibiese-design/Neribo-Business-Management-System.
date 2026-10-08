// update-business-profile (Super Admin only): changes the business name, currency and timezone.
// Request:  { name: string, currency: string, timezone: string }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember, requireRole } from "../_shared/auth.ts";
import { fsGet, fsSet, mirrorBusiness } from "../_shared/firebase.ts";
import { logServerAction } from "../_shared/serverAudit.ts";

// Same order as the app's Role enum (Super Admin is not assignable).
const ASSIGNABLE_ROLES = [
  "manager",
  "receptionist",
  "accountant",
  "staff",
  "waiter",
  "housekeeping",
  "bar_attendant",
  "laundry_valet",
  "operations_manager",
  "maintenance_technician",
  "security_guard",
  "driver",
  "restaurant_attendant",
  "kitchen_staff",
  "gym_staff",
];

const SYMBOLS: Record<string, string> = {
  NGN: "₦",
  USD: "$",
  GBP: "£",
  EUR: "€",
  GHS: "GH₵",
  KES: "KSh",
  ZAR: "R",
  XOF: "CFA",
};

const GENERIC = "Could not update the business profile. Please try again.";

function currencySymbol(code: string): string {
  return SYMBOLS[code] ?? `${code} `;
}

function isValidTimezone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: tz });
    return true;
  } catch (_e) {
    return false;
  }
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const caller = await requireMember(req);
    requireRole(caller, ["super_admin"]);

    // ---- 1. Validate the request ----
    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch (_e) {
      return fail(400, "Invalid request.");
    }
    if (!body || typeof body !== "object") return fail(400, "Invalid request.");

    const name = typeof body.name === "string" ? body.name.trim() : "";
    if (name.length < 2 || name.length > 120) {
      return fail(400, "The business name must be 2 to 120 characters.");
    }
    const currencyRaw = typeof body.currency === "string" ? body.currency.trim() : "";
    if (!/^[A-Za-z]{3}$/.test(currencyRaw)) {
      return fail(400, "The currency must be a 3-letter code, for example NGN.");
    }
    const currency = currencyRaw.toUpperCase();
    const timezone = typeof body.timezone === "string" ? body.timezone.trim() : "";
    if (!timezone || !isValidTimezone(timezone)) {
      return fail(400, "That timezone is not valid. Use a name like Africa/Lagos.");
    }
    const symbol = currencySymbol(currency);

    const db = admin();

    // ---- 2. Load the current business row (for the audit trail, the mirror and an undo) ----
    const bizRes = await db
      .from("businesses")
      .select("id, name, code, business_type, currency, currency_symbol, timezone")
      .eq("id", caller.businessId)
      .maybeSingle();
    if (bizRes.error || !bizRes.data) throw new Error("business lookup failed");
    const before = bizRes.data;

    // ---- 3. Save ----
    const save = await db
      .from("businesses")
      .update({ name, currency, currency_symbol: symbol, timezone })
      .eq("id", caller.businessId);
    if (save.error) {
      console.error("update-business-profile: save failed:", save.error.message);
      return fail(500, GENERIC);
    }

    // ---- 4. Mirror to Firestore so every signed-in phone updates live; undo the save if that fails ----
    try {
      let mirrored = false;
      try {
        // Keep maintenance mode exactly as it is in Firestore (the Settings app writes it there).
        const current = await fsGet(`businesses/${caller.businessId}`);
        if (current && typeof current.maintenanceMode === "boolean") {
          const rolesRes = await db
            .from("business_roles")
            .select("role, enabled")
            .eq("business_id", caller.businessId);
          if (!rolesRes.error) {
            const on = new Set(
              (rolesRes.data ?? []).filter((r) => r.enabled === true).map((r) => r.role as string),
            );
            await mirrorBusiness({
              id: before.id as string,
              name,
              code: before.code as string,
              enabledRoles: ASSIGNABLE_ROLES.filter((r) => on.has(r)),
              currency,
              currencySymbol: symbol,
              timezone,
              businessType: before.business_type as string,
              maintenanceMode: current.maintenanceMode === true,
              maintenanceMessage: typeof current.maintenanceMessage === "string"
                ? current.maintenanceMessage
                : null,
            });
            mirrored = true;
          }
        }
      } catch (e) {
        console.error("update-business-profile: full mirror not possible:", e instanceof Error ? e.message : "unknown");
      }
      if (!mirrored) {
        // Could not read the current values: change only the fields that changed (merge).
        await fsSet(`businesses/${caller.businessId}`, {
          name,
          currency,
          currencySymbol: symbol,
          timezone,
        }, true);
      }
    } catch (e) {
      console.error("update-business-profile: mirror failed, undoing:", e instanceof Error ? e.message : "unknown");
      const undo = await db
        .from("businesses")
        .update({
          name: before.name,
          currency: before.currency,
          currency_symbol: before.currency_symbol,
          timezone: before.timezone,
        })
        .eq("id", caller.businessId);
      if (undo.error) console.error("update-business-profile: undo failed:", undo.error.message);
      return fail(500, GENERIC);
    }

    // ---- 5. Audit trail (never throws) ----
    await logServerAction(
      caller.businessId,
      caller.userId,
      caller.name,
      caller.role,
      "business_profile_updated",
      "businesses",
      caller.businessId,
      {
        name: before.name,
        currency: before.currency,
        timezone: before.timezone,
      },
      { name, currency, timezone },
    );

    return ok();
  } catch (e) {
    return handleError(e);
  }
});
