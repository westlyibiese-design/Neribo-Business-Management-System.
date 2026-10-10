// Writes an in-app notification and sends an Android push without a signed-in caller (Phase 24 server job).
import { admin } from "./supabaseAdmin.ts";
import { accessToken, fsAdd, serviceAccount } from "./firebase.ts";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const MAX_TOKENS = 500;

export interface ServerNotification {
  type: string;
  title: string;
  message: string;
  forUserIds: string[];
  severity?: string;
  link?: string;
}

async function sendOne(projectId: string, bearer: string, token: string, n: ServerNotification, link: string, notificationId: string): Promise<"sent" | "failed" | "dead"> {
  try {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: n.title, body: n.message },
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
    return "failed";
  } catch (_e) {
    return "failed";
  }
}

/** Best-effort push. Never throws. */
async function pushTo(bid: string, n: ServerNotification, link: string, notificationId: string): Promise<void> {
  try {
    const db = admin();
    const members = await db
      .from("business_members")
      .select("user_id")
      .eq("business_id", bid)
      .eq("status", "active")
      .in("user_id", n.forUserIds);
    if (members.error) return;
    const userIds = (members.data ?? []).map((r) => r.user_id as string);
    if (userIds.length === 0) return;

    const rows = await db.from("push_tokens").select("token").eq("business_id", bid).in("user_id", userIds).limit(MAX_TOKENS);
    if (rows.error) return;
    const tokens = [...new Set((rows.data ?? []).map((r) => r.token as string))].slice(0, MAX_TOKENS);
    if (tokens.length === 0) return;

    const projectId = serviceAccount().project_id;
    const bearer = await accessToken([FCM_SCOPE]);
    const dead: string[] = [];
    for (let i = 0; i < tokens.length; i += 25) {
      const batch = tokens.slice(i, i + 25);
      const results = await Promise.all(batch.map((t) => sendOne(projectId, bearer, t, n, link, notificationId)));
      results.forEach((r, idx) => {
        if (r === "dead") dead.push(batch[idx]);
      });
    }
    if (dead.length > 0) await db.from("push_tokens").delete().in("token", dead);
  } catch (_e) {
    console.error("serverNotify: push failed");
  }
}

/** Creates businesses/{bid}/notifications/{auto id} (Phase 10 fields) and then tries to push. Does nothing when nobody is addressed. */
export async function serverNotify(bid: string, n: ServerNotification): Promise<void> {
  const ids = [...new Set((n.forUserIds ?? []).filter((x) => typeof x === "string" && x))];
  if (ids.length === 0) return;
  const link = n.link && n.link.trim() ? n.link.trim() : "/admin/dashboard";
  const notificationId = await fsAdd(`businesses/${bid}/notifications`, {
    type: n.type,
    title: n.title,
    message: n.message,
    severity: n.severity ?? "info",
    link,
    forRoles: [],
    forUserIds: ids,
    excludeUserId: null,
    actorId: "system",
    readBy: [],
    deletedBy: [],
    createdAt: new Date(),
  });
  await pushTo(bid, { ...n, forUserIds: ids }, link, notificationId);
}
