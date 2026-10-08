// send-push (any active member): sends an Android push (Firebase Cloud Messaging) to staff of the caller's business.
// Request:  { forRoles?, forUserIds?, excludeUserId?, title, body, link?, notificationId? }
// Response: { ok:true, sent, failed }  or  { ok:false, error }
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { accessToken, serviceAccount } from "../_shared/firebase.ts";

const MAX_ROLES = 10;
const MAX_TOKENS = 500;
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

function stringList(v: unknown, max: number): string[] {
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  for (const item of v) {
    if (typeof item === "string" && item.trim()) out.push(item.trim());
    if (out.length >= max) break;
  }
  return out;
}

async function sendOne(
  projectId: string,
  bearer: string,
  token: string,
  title: string,
  body: string,
  link: string,
  notificationId: string,
): Promise<"sent" | "failed" | "dead"> {
  try {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: { title, body },
          data: { link, notificationId },
          android: { priority: "HIGH", notification: { channel_id: "nbms_default" } },
        },
      }),
    });
    if (res.ok) return "sent";
    let status = "";
    try {
      const err = await res.json() as { error?: { status?: string; details?: { errorCode?: string }[] } };
      status = err?.error?.status ?? "";
      const code = err?.error?.details?.find((d) => d?.errorCode)?.errorCode ?? "";
      if (code === "UNREGISTERED") status = "UNREGISTERED";
    } catch (_e) {
      // ignore body parse problems
    }
    if (status === "UNREGISTERED" || status === "INVALID_ARGUMENT" || res.status === 404) return "dead";
    console.error("send-push: FCM answered", res.status, status);
    return "failed";
  } catch (_e) {
    console.error("send-push: FCM request failed");
    return "failed";
  }
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const caller = await requireMember(req);

    let payload: Record<string, unknown>;
    try {
      payload = await req.json();
    } catch (_e) {
      return fail(400, "Invalid request.");
    }
    if (!payload || typeof payload !== "object") return fail(400, "Invalid request.");

    const title = typeof payload.title === "string" ? payload.title.trim() : "";
    const body = typeof payload.body === "string" ? payload.body.trim() : "";
    if (!title || !body) return fail(400, "title and body are required.");
    const link = typeof payload.link === "string" && payload.link.trim() ? payload.link.trim() : "/admin/dashboard";
    const notificationId = typeof payload.notificationId === "string" ? payload.notificationId.trim() : "";
    const excludeUserId = typeof payload.excludeUserId === "string" && payload.excludeUserId.trim() ? payload.excludeUserId.trim() : null;
    const forRoles = stringList(payload.forRoles, MAX_ROLES);
    const forUserIds = stringList(payload.forUserIds, 500);

    const db = admin();

    // Work out who gets it. Everything is limited to the caller's own business.
    const recipients = new Set<string>();
    const roleFilter = new Set(forRoles);
    if (forRoles.length > 0) roleFilter.add("super_admin"); // the Super Admin sees everything
    if (roleFilter.size > 0) {
      const byRole = await db
        .from("business_members")
        .select("user_id")
        .eq("business_id", caller.businessId)
        .eq("status", "active")
        .in("role", [...roleFilter]);
      if (byRole.error) throw new Error("role lookup failed");
      for (const r of byRole.data ?? []) recipients.add(r.user_id as string);
    }
    if (forUserIds.length > 0) {
      const byId = await db
        .from("business_members")
        .select("user_id")
        .eq("business_id", caller.businessId)
        .eq("status", "active")
        .in("user_id", forUserIds);
      if (byId.error) throw new Error("user lookup failed");
      for (const r of byId.data ?? []) recipients.add(r.user_id as string);
    }
    if (excludeUserId) recipients.delete(excludeUserId);

    if (recipients.size === 0) return ok({ sent: 0, failed: 0 });

    const tokenRows = await db
      .from("push_tokens")
      .select("token")
      .eq("business_id", caller.businessId)
      .in("user_id", [...recipients])
      .limit(MAX_TOKENS);
    if (tokenRows.error) throw new Error("token lookup failed");
    const tokens = [...new Set((tokenRows.data ?? []).map((r) => r.token as string))].slice(0, MAX_TOKENS);
    if (tokens.length === 0) return ok({ sent: 0, failed: 0 });

    const projectId = serviceAccount().project_id;
    const bearer = await accessToken([FCM_SCOPE]);

    let sent = 0;
    let failed = 0;
    const dead: string[] = [];
    // Small batches keep the function fast without flooding Google.
    for (let i = 0; i < tokens.length; i += 25) {
      const batch = tokens.slice(i, i + 25);
      const results = await Promise.all(batch.map((t) => sendOne(projectId, bearer, t, title, body, link, notificationId)));
      results.forEach((r, idx) => {
        if (r === "sent") sent++;
        else {
          failed++;
          if (r === "dead") dead.push(batch[idx]);
        }
      });
    }

    if (dead.length > 0) {
      const del = await db.from("push_tokens").delete().in("token", dead);
      if (del.error) console.error("send-push: could not remove dead tokens:", del.error.message);
    }
    return ok({ sent, failed });
  } catch (e) {
    return handleError(e);
  }
});
