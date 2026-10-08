// set-business-roles (Super Admin only): turns the business's staff roles on or off.
// Request:  { enabledRoles: string[] }   (the complete list of roles the business should use afterwards)
// Response: { ok:true, enabledRoles }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember, requireRole } from "../_shared/auth.ts";
import { mirrorBusiness } from "../_shared/firebase.ts";
import { logServerAction } from "../_shared/serverAudit.ts";

// The 15 assignable roles, in the same order as the app's Role enum (Super Admin is not assignable).
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

const LABELS: Record<string, string> = {
  manager: "Manager",
  receptionist: "Receptionist",
  accountant: "Accountant",
  staff: "Staff",
  waiter: "Waiter",
  housekeeping: "Housekeeping",
  bar_attendant: "Bar Attendant",
  laundry_valet: "Laundry Valet",
  operations_manager: "Operations Manager",
  maintenance_technician: "Maintenance Technician",
  security_guard: "Security Guard",
  driver: "Driver",
  restaurant_attendant: "Restaurant Attendant",
  kitchen_staff: "Kitchen Staff",
  gym_staff: "Gym Staff",
};

const GENERIC = "Could not update the roles. Please try again.";

/** Keeps only assignable roles, in the standard order. */
function inOrder(roles: Iterable<string>): string[] {
  const set = new Set(roles);
  return ASSIGNABLE_ROLES.filter((r) => set.has(r));
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
    if (!body || typeof body !== "object" || !Array.isArray(body.enabledRoles)) {
      return fail(400, "enabledRoles must be a list of role keys.");
    }
    const requested = new Set<string>();
    for (const r of body.enabledRoles) {
      if (typeof r !== "string" || !ASSIGNABLE_ROLES.includes(r)) {
        return fail(400, "One of the chosen roles is not valid.");
      }
      requested.add(r);
    }

    const db = admin();

    // ---- 2. Load the business and its current role switches ----
    const bizRes = await db
      .from("businesses")
      .select("id, name, code, business_type, currency, currency_symbol, timezone, maintenance_mode, maintenance_message")
      .eq("id", caller.businessId)
      .maybeSingle();
    if (bizRes.error || !bizRes.data) throw new Error("business lookup failed");
    const biz = bizRes.data;

    const rolesRes = await db
      .from("business_roles")
      .select("role, enabled")
      .eq("business_id", caller.businessId);
    if (rolesRes.error) throw new Error("roles lookup failed");
    const previous = inOrder(
      (rolesRes.data ?? []).filter((row) => row.enabled === true).map((row) => row.role as string),
    );

    // ---- 3. A role being turned OFF must have no active staff ----
    const turnedOff = previous.filter((r) => !requested.has(r));
    for (const role of turnedOff) {
      const countRes = await db
        .from("business_members")
        .select("user_id", { count: "exact", head: true })
        .eq("business_id", caller.businessId)
        .eq("role", role)
        .eq("status", "active");
      if (countRes.error) throw new Error("member count failed");
      const n = countRes.count ?? 0;
      if (n > 0) {
        return fail(409, `Can't turn off ${LABELS[role] ?? role}: ${n} active user(s) still have this role.`);
      }
    }

    // ---- 4. Save all 15 rows in one statement ----
    const next = inOrder(requested);
    const rows = ASSIGNABLE_ROLES.map((role) => ({
      business_id: caller.businessId,
      role,
      enabled: requested.has(role),
    }));
    const save = await db.from("business_roles").upsert(rows, { onConflict: "business_id,role" });
    if (save.error) {
      console.error("set-business-roles: save failed:", save.error.message);
      return fail(500, GENERIC);
    }

    // ---- 5. Mirror to Firestore so every signed-in phone updates live; undo the save if that fails ----
    try {
      await mirrorBusiness({
        id: biz.id as string,
        name: biz.name as string,
        code: biz.code as string,
        enabledRoles: next,
        currency: biz.currency as string,
        currencySymbol: biz.currency_symbol as string,
        timezone: biz.timezone as string,
        businessType: biz.business_type as string,
        maintenanceMode: biz.maintenance_mode === true,
        maintenanceMessage: (biz.maintenance_message as string | null) ?? null,
      });
    } catch (e) {
      console.error("set-business-roles: mirror failed, undoing:", e instanceof Error ? e.message : "unknown");
      const undoRows = ASSIGNABLE_ROLES.map((role) => ({
        business_id: caller.businessId,
        role,
        enabled: previous.includes(role),
      }));
      const undo = await db.from("business_roles").upsert(undoRows, { onConflict: "business_id,role" });
      if (undo.error) console.error("set-business-roles: undo failed:", undo.error.message);
      return fail(500, GENERIC);
    }

    // ---- 6. Audit trail (never throws) ----
    await logServerAction(
      caller.businessId,
      caller.userId,
      caller.name,
      caller.role,
      "roles_updated",
      "business_roles",
      caller.businessId,
      { enabledRoles: previous },
      { enabledRoles: next },
    );

    return ok({ enabledRoles: next });
  } catch (e) {
    return handleError(e);
  }
});
