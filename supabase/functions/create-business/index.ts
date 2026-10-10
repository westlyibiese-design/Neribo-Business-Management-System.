// create-business (public): registers a new business and its Super Admin.
// The email must first be verified in the app with a 6-digit code; the app then sends the access token that code produced.
// Request:  { ownerName, email, password, phone?, businessName, businessType?: "hotel", enabledRoles: string[], verificationToken }
// Response: { ok:true, businessId, businessCode }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { fsDelete, fsSet, mirrorBusiness, mirrorMember } from "../_shared/firebase.ts";

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

const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const PHONE_RE = /^\+?[0-9\s\-()]+$/;
const MAX_PER_HOUR = 5;
const GENERIC_FAILURE = "Could not create your business. Please try again.";
// Must read exactly like MSG_VERIFY_EXPIRED in the Android app (RegistrationApi.kt).
const VERIFY_EXPIRED = "Your email verification has expired. Please verify your email again.";

function asText(v: unknown): string {
  return typeof v === "string" ? v.trim() : "";
}

function passwordProblem(p: string): string | null {
  const okRule = p.length >= 8 && /\p{L}/u.test(p) && /[0-9]/.test(p);
  if (!okRule) return "Password must be at least 8 characters with a letter and a number.";
  if (p.length > 72) return "Password must be 72 characters or fewer.";
  return null;
}

function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const first = forwarded.split(",")[0]?.trim();
  return first && first.length <= 64 ? first : "unknown";
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  // ---- 1. Validate -------------------------------------------------------
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch (_e) {
    return fail(400, "Invalid request.");
  }
  if (!body || typeof body !== "object") return fail(400, "Invalid request.");

  const ownerName = asText(body.ownerName);
  const businessName = asText(body.businessName);
  const email = asText(body.email).toLowerCase();
  const password = typeof body.password === "string" ? body.password : "";
  const phone = asText(body.phone);
  const businessType = body.businessType === undefined || body.businessType === null ? "hotel" : body.businessType;

  if (ownerName.length < 2 || ownerName.length > 120) return fail(400, "Your name must be 2 to 120 characters.");
  if (businessName.length < 2 || businessName.length > 120) return fail(400, "Business name must be 2 to 120 characters.");
  if (!EMAIL_RE.test(email)) return fail(400, "Please enter a valid email address.");
  const pwProblem = passwordProblem(password);
  if (pwProblem) return fail(400, pwProblem);
  if (phone) {
    const digits = (phone.match(/[0-9]/g) ?? []).length;
    if (!PHONE_RE.test(phone) || digits < 7 || digits > 15) return fail(400, "Please enter a valid phone number.");
  }
  if (businessType !== "hotel") return fail(400, "Only the hotel business type is available right now.");

  if (!Array.isArray(body.enabledRoles)) return fail(400, "Please choose the roles your business uses.");
  const chosen: string[] = [];
  for (const r of body.enabledRoles) {
    if (typeof r !== "string" || !ASSIGNABLE_ROLES.includes(r)) return fail(400, "One of the chosen roles is not valid.");
    if (!chosen.includes(r)) chosen.push(r);
  }

  const db = admin();
  const ip = clientIp(req);

  // ---- 2. Abuse guard ----------------------------------------------------
  try {
    const since = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    const { count, error } = await db
      .from("signup_attempts")
      .select("id", { count: "exact", head: true })
      .eq("ip", ip)
      .gte("created_at", since);
    if (error) throw error;
    if ((count ?? 0) >= MAX_PER_HOUR) {
      return fail(429, "Too many sign-ups from this connection. Please try again later.");
    }
  } catch (e) {
    console.error("create-business: abuse check failed:", e instanceof Error ? e.message : "unknown");
    return fail(500, GENERIC_FAILURE);
  }

  // ---- 3. Use the account that the verified email code created ------------
  // The app proves the email with a 6-digit code and sends the resulting access token. The account comes from
  // that token, never from the typed email, so nobody can register an address they cannot read mail for.
  const token = asText(body.verificationToken);
  if (!token) return fail(400, "Please verify your email first.");

  let userId = "";
  try {
    const got = await db.auth.getUser(token);
    const verified = got.data?.user;
    if (got.error || !verified) return fail(401, VERIFY_EXPIRED);
    if ((verified.email ?? "").toLowerCase() !== email || !verified.email_confirmed_at) return fail(401, VERIFY_EXPIRED);
    userId = verified.id;

    // An address that already runs a business (or belongs to staff) cannot register again.
    const existing = await db.from("business_members").select("user_id").eq("user_id", userId).limit(1);
    if (existing.error) throw new Error("member lookup failed");
    if ((existing.data ?? []).length > 0) return fail(409, "An account with this email already exists.");

    const updated = await db.auth.admin.updateUserById(userId, {
      password,
      email_confirm: true,
      user_metadata: { name: ownerName },
    });
    if (updated.error) {
      console.error("create-business: set password failed:", updated.error.message);
      return fail(500, GENERIC_FAILURE);
    }
  } catch (e) {
    console.error("create-business: verification step threw:", e instanceof Error ? e.message : "unknown");
    return fail(500, GENERIC_FAILURE);
  }

  // ---- 4 & 5. Business, roles, member, Firebase mirrors (rolled back on any failure) ----
  let businessId = "";
  let businessCode = "";
  let mirroredBusiness = false;
  let mirroredMember = false;

  try {
    const codeRes = await db.rpc("generate_business_code");
    if (codeRes.error || typeof codeRes.data !== "string") throw new Error("code generation failed");
    businessCode = codeRes.data;

    const bizRes = await db
      .from("businesses")
      .insert({ name: businessName, code: businessCode, business_type: "hotel", owner_user_id: userId })
      .select("id")
      .single();
    if (bizRes.error || !bizRes.data) throw new Error("business insert failed");
    businessId = bizRes.data.id as string;

    const roleRows = ASSIGNABLE_ROLES.map((role) => ({
      business_id: businessId,
      role,
      enabled: chosen.includes(role),
    }));
    const rolesRes = await db.from("business_roles").insert(roleRows);
    if (rolesRes.error) throw new Error("roles insert failed");

    const memberRes = await db.from("business_members").insert({
      user_id: userId,
      business_id: businessId,
      role: "super_admin",
      name: ownerName,
      email,
      phone: phone || null,
      status: "active",
      uses_pin: false,
      created_by: userId,
    });
    if (memberRes.error) throw new Error("member insert failed");

    mirroredBusiness = true;
    await mirrorBusiness({
      id: businessId,
      name: businessName,
      code: businessCode,
      enabledRoles: chosen,
      currency: "NGN",
      currencySymbol: "₦",
      timezone: "Africa/Lagos",
      businessType: "hotel",
      maintenanceMode: false,
      maintenanceMessage: null,
    });
    await fsSet(`businesses/${businessId}`, { createdAt: new Date() });

    mirroredMember = true;
    await mirrorMember(businessId, {
      userId,
      role: "super_admin",
      name: ownerName,
      email,
      phone: phone || null,
      status: "active",
      usesPin: false,
    });

    // Count this sign-up for the abuse guard (best effort).
    await db.from("signup_attempts").insert({ ip });

    // ---- 6. Done -----------------------------------------------------------
    return ok({ businessId, businessCode });
  } catch (e) {
    console.error("create-business: failed, rolling back:", e instanceof Error ? e.message : "unknown");
    // ---- 7. Roll back in reverse order. Each step is independent and never throws. ----
    if (mirroredMember) {
      try { await fsDelete(`businesses/${businessId}/users/${userId}`); } catch (_e) { /* ignore */ }
    }
    if (mirroredBusiness) {
      try { await fsDelete(`businesses/${businessId}`); } catch (_e) { /* ignore */ }
    }
    if (businessId) {
      // Deleting the business also removes its business_roles and business_members rows (cascade).
      try { await db.from("business_members").delete().eq("user_id", userId); } catch (_e) { /* ignore */ }
      try { await db.from("business_roles").delete().eq("business_id", businessId); } catch (_e) { /* ignore */ }
      try { await db.from("businesses").delete().eq("id", businessId); } catch (_e) { /* ignore */ }
    }
    try { await db.auth.admin.deleteUser(userId); } catch (_e) { /* ignore */ }
    return fail(500, GENERIC_FAILURE);
  }
});
