// create-user (Super Admin only): creates a staff account (email + password, optional shared-device PIN).
// Request:  { name, email, password, phone?, role, pin? }
// Response: { ok:true, userId }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember, requireRole } from "../_shared/auth.ts";
import { fsDelete, mirrorMember } from "../_shared/firebase.ts";
import { hashPin, PepperMissingError, pinLookup, validPin } from "../_shared/pin.ts";
import { logServerAction } from "../_shared/serverAudit.ts";

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
const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const PHONE_RE = /^\+?[0-9\s\-()]+$/;
const GENERIC = "Could not create the account. Please try again.";

function asText(v: unknown): string {
  return typeof v === "string" ? v.trim() : "";
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  let createdAuthId = "";
  const db = (() => {
    try {
      return admin();
    } catch (_e) {
      return null;
    }
  })();

  try {
    const caller = await requireMember(req);
    requireRole(caller, ["super_admin"]);
    if (!db) return fail(500, "Server is not configured.");

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch (_e) {
      return fail(400, "Invalid request.");
    }
    if (!body || typeof body !== "object") return fail(400, "Invalid request.");

    const name = asText(body.name);
    const email = asText(body.email).toLowerCase();
    const password = typeof body.password === "string" ? body.password : "";
    const phone = asText(body.phone);
    const role = asText(body.role);
    const hasPin = body.pin !== undefined && body.pin !== null && body.pin !== "";

    if (!name || !email || !password || !role) return fail(400, "name, email, password, and role are required.");
    if (name.length < 2 || name.length > 120) return fail(400, "Name must be 2 to 120 characters.");
    if (!EMAIL_RE.test(email)) return fail(400, "Please enter a valid email address.");
    if (password.length < 8) return fail(400, "Password must be at least 8 characters.");
    if (password.length > 72) return fail(400, "Password must be 72 characters or fewer.");
    if (phone) {
      const digits = (phone.match(/[0-9]/g) ?? []).length;
      if (!PHONE_RE.test(phone) || digits < 7 || digits > 15) return fail(400, "Please enter a valid phone number.");
    }
    if (hasPin && !validPin(body.pin)) return fail(400, "PIN must be 4 to 6 digits.");

    if (role === "super_admin") return fail(400, "A Super Admin account cannot be created here.");
    if (!ASSIGNABLE_ROLES.includes(role)) return fail(400, "That role is not enabled for your business.");
    const roleRes = await db
      .from("business_roles")
      .select("enabled")
      .eq("business_id", caller.businessId)
      .eq("role", role)
      .maybeSingle();
    if (roleRes.error) throw new Error("role lookup failed");
    if (!roleRes.data || roleRes.data.enabled !== true) return fail(400, "That role is not enabled for your business.");

    if (hasPin && !PIN_ROLES.includes(role)) return fail(400, "This role must use email login.");

    // Work out the PIN values first, so a missing PIN_PEPPER stops us before anything is created.
    let lookup: string | null = null;
    let hash: string | null = null;
    if (hasPin) {
      const pin = body.pin as string;
      lookup = await pinLookup(caller.businessId, pin);
      hash = await hashPin(pin);
    }

    // Sign-in account
    const created = await db.auth.admin.createUser({
      email,
      password,
      email_confirm: true,
      user_metadata: { name },
    });
    if (created.error || !created.data?.user) {
      const text = `${created.error?.message ?? ""} ${(created.error as { code?: string } | null)?.code ?? ""}`
        .toLowerCase();
      if (text.includes("already") || text.includes("exists") || text.includes("registered")) {
        return fail(409, "An account with this email already exists.");
      }
      console.error("create-user: createUser failed:", created.error?.message ?? "no user returned");
      return fail(500, GENERIC);
    }
    createdAuthId = created.data.user.id;

    // Member row
    const insert = await db.from("business_members").insert({
      user_id: createdAuthId,
      business_id: caller.businessId,
      role,
      name,
      email,
      phone: phone || null,
      status: "active",
      uses_pin: hasPin,
      pin_lookup: lookup,
      pin_hash: hash,
      created_by: caller.userId,
    });
    if (insert.error) {
      try {
        await db.auth.admin.deleteUser(createdAuthId);
      } catch (_e) {
        // ignore
      }
      if ((insert.error as { code?: string }).code === "23505") {
        return fail(409, "That PIN is already in use. Choose another.");
      }
      console.error("create-user: member insert failed:", insert.error.message);
      return fail(500, GENERIC);
    }

    // Firestore mirror (rules read it). Roll everything back if it fails.
    try {
      await mirrorMember(caller.businessId, {
        userId: createdAuthId,
        role,
        name,
        email,
        phone: phone || null,
        status: "active",
        usesPin: hasPin,
      });
    } catch (e) {
      console.error("create-user: mirror failed, rolling back:", e instanceof Error ? e.message : "unknown");
      try {
        await fsDelete(`businesses/${caller.businessId}/users/${createdAuthId}`);
      } catch (_e) {
        // ignore
      }
      try {
        await db.from("business_members").delete().eq("user_id", createdAuthId);
      } catch (_e) {
        // ignore
      }
      try {
        await db.auth.admin.deleteUser(createdAuthId);
      } catch (_e) {
        // ignore
      }
      return fail(500, GENERIC);
    }

    await logServerAction(
      caller.businessId,
      caller.userId,
      caller.name,
      caller.role,
      "user_created",
      "users",
      createdAuthId,
      null,
      { name, email, role },
    );
    return ok({ userId: createdAuthId });
  } catch (e) {
    if (e instanceof PepperMissingError) return fail(500, "Server is not configured.");
    return handleError(e);
  }
});
