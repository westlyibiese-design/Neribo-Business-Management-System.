// set-user-status (Super Admin only): suspends or reactivates a staff member of the same business.
// Request:  { userId, status: "active" | "suspended" }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember, requireRole } from "../_shared/auth.ts";
import { mirrorMember } from "../_shared/firebase.ts";
import { logServerAction } from "../_shared/serverAudit.ts";

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
    const status = body.status;
    if (!userId) return fail(400, "userId is required.");
    if (status !== "active" && status !== "suspended") return fail(400, "Status must be active or suspended.");
    if (status === "suspended" && userId === caller.userId) return fail(400, "You cannot suspend your own account.");

    const db = admin();
    const target = await db
      .from("business_members")
      .select("user_id, business_id, role, name, email, phone, status, uses_pin")
      .eq("user_id", userId)
      .maybeSingle();
    if (target.error) throw new Error("member lookup failed");
    const t = target.data;
    if (!t || t.business_id !== caller.businessId) return fail(404, "User not found.");

    const previous = t.status as string;
    const update = await db
      .from("business_members")
      .update({ status })
      .eq("user_id", userId)
      .eq("business_id", caller.businessId);
    if (update.error) {
      console.error("set-user-status: update failed:", update.error.message);
      return fail(500, "Could not change the account status. Please try again.");
    }

    // Mirror first so the live-data rules react right away.
    try {
      await mirrorMember(caller.businessId, {
        userId,
        role: t.role as string,
        name: t.name as string,
        email: t.email as string | null,
        phone: t.phone as string | null,
        status,
        usesPin: t.uses_pin === true,
      });
    } catch (e) {
      console.error("set-user-status: mirror failed, undoing:", e instanceof Error ? e.message : "unknown");
      await db.from("business_members").update({ status: previous }).eq("user_id", userId);
      return fail(500, "Could not change the account status. Please try again.");
    }

    // Block (or unblock) new and refreshed sign-ins for this user. A banned user cannot refresh a session,
    // so any open session ends within the hour at the latest; the status checks end data access immediately.
    const ban = await db.auth.admin.updateUserById(userId, {
      ban_duration: status === "suspended" ? "876000h" : "none",
    });
    if (ban.error) console.error("set-user-status: ban update failed:", ban.error.message);

    await logServerAction(
      caller.businessId,
      caller.userId,
      caller.name,
      caller.role,
      status === "suspended" ? "user_suspended" : "user_reactivated",
      "users",
      userId,
      { status: previous },
      { status },
    );
    return ok();
  } catch (e) {
    return handleError(e);
  }
});
