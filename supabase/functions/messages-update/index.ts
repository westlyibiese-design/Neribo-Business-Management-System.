// messages-update: change one message of the caller's own business.
// Request:  POST { id, action: "mark_read" | "set_reply_status" | "soft_delete", replyStatus?: "none" | "pending" | "replied" }
// Response: { ok:true }  or  { ok:false, error }
// Roles:    super_admin, manager, receptionist (active members only)
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";

const ALLOWED_ROLES = ["super_admin", "manager", "receptionist"];
const ACTIONS = ["mark_read", "set_reply_status", "soft_delete"];
const REPLY_STATUSES = ["none", "pending", "replied"];
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Method not allowed.");

  try {
    const member = await requireMember(req);
    if (member.status !== "active" || !ALLOWED_ROLES.includes(member.role)) {
      return fail(403, "You don't have permission to update the Message Inbox.");
    }

    let body: Record<string, unknown> = {};
    let bodyOk = true;
    try {
      const parsed: unknown = await req.json();
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        body = parsed as Record<string, unknown>;
      } else {
        bodyOk = false;
      }
    } catch (_e) {
      bodyOk = false;
    }

    const id = bodyOk && typeof body.id === "string" ? body.id.trim() : "";
    if (!id) return fail(400, "id is required.");

    const action = typeof body.action === "string" ? body.action.trim() : "";
    if (!action) return fail(400, "action is required.");
    if (!ACTIONS.includes(action)) return fail(400, "Unknown action.");

    const replyStatus = typeof body.replyStatus === "string" ? body.replyStatus : "";
    if (action === "set_reply_status" && !REPLY_STATUSES.includes(replyStatus)) {
      return fail(400, "A valid replyStatus is required for set_reply_status.");
    }

    // A malformed id can never match a message, so answer like any other missing message.
    if (!UUID_RE.test(id)) return fail(404, "Message not found.");

    let changes: Record<string, unknown>;
    if (action === "mark_read") {
      changes = { status: "read", read_at: new Date().toISOString() };
    } else if (action === "set_reply_status") {
      changes = {
        reply_status: replyStatus,
        replied_at: replyStatus === "replied" ? new Date().toISOString() : null,
      };
    } else {
      changes = { is_deleted: true };
    }

    const { data, error } = await admin()
      .from("messages")
      .update(changes)
      .eq("id", id)
      .eq("business_id", member.businessId)
      .select("id");
    if (error) {
      console.error("messages-update: update failed:", error.message);
      return fail(500, "Couldn't update the message.");
    }
    if (!data || data.length === 0) return fail(404, "Message not found.");
    return ok();
  } catch (e) {
    return handleError(e);
  }
});
