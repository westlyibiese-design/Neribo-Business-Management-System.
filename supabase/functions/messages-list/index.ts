// messages-list: the Message Inbox (website enquiries) of the caller's own business.
// Request:  POST {} (Bearer token)
// Response: { ok:true, messages:[<public.messages row>, ...] }  newest first, is_deleted = false only
// Roles:    super_admin, manager, receptionist (active members only)
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";

const ALLOWED_ROLES = ["super_admin", "manager", "receptionist"];

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Method not allowed.");

  try {
    const member = await requireMember(req);
    if (member.status !== "active" || !ALLOWED_ROLES.includes(member.role)) {
      return fail(403, "You don't have permission to view the Message Inbox.");
    }

    const { data, error } = await admin()
      .from("messages")
      .select("*")
      .eq("business_id", member.businessId)
      .eq("is_deleted", false)
      .order("created_at", { ascending: false });
    if (error) {
      console.error("messages-list: query failed:", error.message);
      return fail(500, "Couldn't load messages.");
    }
    return ok({ messages: data ?? [] });
  } catch (e) {
    return handleError(e);
  }
});
