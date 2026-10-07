// reset-password (Super Admin only): sets a new password for a staff member of the same business.
// Request:  { userId, newPassword }
// Response: { ok:true }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember, requireRole } from "../_shared/auth.ts";
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
    const newPassword = typeof body.newPassword === "string" ? body.newPassword : "";
    if (!userId) return fail(400, "userId is required.");
    if (newPassword.length < 8) return fail(400, "Password must be at least 8 characters.");
    if (newPassword.length > 72) return fail(400, "Password must be 72 characters or fewer.");
    if (userId === caller.userId) return fail(400, "Use your profile to change your own password.");

    const db = admin();
    const target = await db
      .from("business_members")
      .select("user_id, business_id, name")
      .eq("user_id", userId)
      .maybeSingle();
    if (target.error) throw new Error("member lookup failed");
    if (!target.data || target.data.business_id !== caller.businessId) return fail(404, "User not found.");

    const updated = await db.auth.admin.updateUserById(userId, { password: newPassword });
    if (updated.error) {
      console.error("reset-password: update failed:", updated.error.message);
      return fail(500, "Could not reset the password. Please try again.");
    }

    await logServerAction(
      caller.businessId,
      caller.userId,
      caller.name,
      caller.role,
      "password_reset",
      "users",
      userId,
      null,
      { name: target.data.name },
    );
    return ok();
  } catch (e) {
    return handleError(e);
  }
});
