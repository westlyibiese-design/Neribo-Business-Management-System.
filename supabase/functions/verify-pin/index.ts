// verify-pin (public): shared-device sign-in with business code + PIN.
// Request:  { businessCode, pin }
// Response: { ok:true, access_token, refresh_token, user:{ name, role } }  or  { ok:false, error }
import { createClient } from "npm:@supabase/supabase-js@2";
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { checkPin, PepperMissingError, pinLookup, validPin } from "../_shared/pin.ts";
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

const BAD_LOGIN = "Invalid business code or PIN.";
const WINDOW_MS = 10 * 60 * 1000;
const MAX_FAILS_PER_IP = 5;
const MAX_FAILS_PER_BUSINESS = 30;

function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const first = forwarded.split(",")[0]?.trim();
  return first && first.length <= 64 ? first : "unknown";
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch (_e) {
    return fail(400, "Invalid request.");
  }
  if (!body || typeof body !== "object") return fail(400, "Invalid request.");

  // 1. Validate input
  const pin = body.pin;
  if (!validPin(pin)) return fail(400, "Enter a valid 4-6 digit PIN.");
  const code = typeof body.businessCode === "string" ? body.businessCode.trim().toUpperCase() : "";
  if (!/^[A-Z0-9]{6,8}$/.test(code)) return fail(400, "Enter your business code.");

  try {
    const db = admin();
    const ip = clientIp(req);

    // 2. Find the business (same message as a wrong PIN, so codes cannot be probed)
    const bizRes = await db.from("businesses").select("id").eq("code", code).maybeSingle();
    if (bizRes.error) throw new Error("business lookup failed");
    if (!bizRes.data) return fail(401, BAD_LOGIN);
    const businessId = bizRes.data.id as string;

    const record = async (success: boolean) => {
      try {
        await db.from("pin_login_attempts").insert({ business_id: businessId, ip, success });
      } catch (_e) {
        // best effort
      }
    };

    // 3. Throttle
    const since = new Date(Date.now() - WINDOW_MS).toISOString();
    const ipFails = await db
      .from("pin_login_attempts")
      .select("id", { count: "exact", head: true })
      .eq("business_id", businessId)
      .eq("success", false)
      .eq("ip", ip)
      .gte("created_at", since);
    const bizFails = await db
      .from("pin_login_attempts")
      .select("id", { count: "exact", head: true })
      .eq("business_id", businessId)
      .eq("success", false)
      .gte("created_at", since);
    if (ipFails.error || bizFails.error) throw new Error("throttle check failed");
    if ((ipFails.count ?? 0) >= MAX_FAILS_PER_IP || (bizFails.count ?? 0) >= MAX_FAILS_PER_BUSINESS) {
      return fail(429, "Too many attempts. Try again in a few minutes.");
    }

    // 4. Find the member and double-check the PIN against its slow hash
    const lookup = await pinLookup(businessId, pin);
    const memberRes = await db
      .from("business_members")
      .select("user_id, name, email, role, status, uses_pin, pin_hash")
      .eq("business_id", businessId)
      .eq("pin_lookup", lookup)
      .maybeSingle();
    if (memberRes.error) throw new Error("member lookup failed");
    const member = memberRes.data;
    const pinOk = !!member && member.uses_pin === true && !!member.pin_hash &&
      await checkPin(pin, member.pin_hash as string);
    if (!member || !pinOk) {
      await record(false);
      return fail(401, BAD_LOGIN);
    }

    // 5. Account must be active and the role must be allowed to use a PIN
    if (member.status !== "active") {
      await record(false);
      return fail(403, "This account is not active. Contact your administrator.");
    }
    if (!PIN_ROLES.includes(member.role as string)) {
      await record(false);
      return fail(403, "This role must sign in with email and password.");
    }
    if (!member.email) throw new Error("member has no email");

    // 6. Mint a real Supabase session for this user (magic-link token, exchanged server-side; no email is sent)
    const link = await db.auth.admin.generateLink({ type: "magiclink", email: member.email as string });
    const tokenHash = link.data?.properties?.hashed_token;
    if (link.error || !tokenHash) throw new Error("could not create sign-in link");

    const url = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    if (!url || !anonKey) throw new Error("missing SUPABASE_URL or SUPABASE_ANON_KEY");
    const anon = createClient(url, anonKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const verified = await anon.auth.verifyOtp({ type: "magiclink", token_hash: tokenHash });
    const session = verified.data?.session;
    if (verified.error || !session) throw new Error("could not open session");

    // 7. Record success + audit
    await record(true);
    await logServerAction(
      businessId,
      member.user_id as string,
      member.name as string,
      member.role as string,
      "pin_login",
      "users",
      member.user_id as string,
      null,
      null,
    );

    // 8. Done
    return ok({
      access_token: session.access_token,
      refresh_token: session.refresh_token,
      user: { name: member.name, role: member.role },
    });
  } catch (e) {
    if (e instanceof PepperMissingError) return fail(500, "Server is not configured.");
    console.error("verify-pin failed:", e instanceof Error ? e.message : "unknown");
    return fail(500, "Something went wrong. Please try again.");
  }
});
