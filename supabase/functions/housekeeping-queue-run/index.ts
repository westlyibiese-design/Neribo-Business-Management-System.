// housekeeping-queue-run: builds the housekeeping queue (checkout cleaning, occupied-room service, balancing, reminders).
// Two ways in (verify_jwt is OFF; this file checks the caller itself):
//   1. The 5-minute schedule sends header x-cron-key  -> runs for EVERY business.
//   2. A signed-in super_admin / manager / operations_manager -> runs for THEIR business only ("Run Queue Now").
// Response: { ok:true, ...RunResult }  (manual)   or  { ok:true, businesses, results:{totals} }  (schedule)
import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { HttpError, requireMember } from "../_shared/auth.ts";
import { runForBusiness, type RunResult } from "../_shared/hkQueue.ts";

const MANUAL_ROLES = ["super_admin", "manager", "operations_manager"];
const WALL_TIME_MS = 100_000;

async function sha256(s: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
}

/** Constant-time comparison (both sides are hashed first so the lengths always match). */
async function safeEqual(a: string, b: string): Promise<boolean> {
  const [x, y] = await Promise.all([sha256(a), sha256(b)]);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

async function cronKeyIsValid(given: string): Promise<boolean> {
  const { data, error } = await admin().rpc("get_cron_key", { p_name: "housekeeping" });
  if (error || typeof data !== "string" || !data) return false;
  return await safeEqual(given, data);
}

type Totals = Omit<RunResult, "ranAt" | "errors"> & { errorCount: number };

function emptyTotals(): Totals {
  return {
    checkoutTasksCreated: 0,
    occupiedServiceTasksCreated: 0,
    skippedAlreadyExisted: 0,
    unassignedCount: 0,
    rebalancedCount: 0,
    overdueCheckoutsNotified: 0,
    cleaningRemindersSent: 0,
    errorCount: 0,
  };
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    const cronKey = req.headers.get("x-cron-key");

    // ---- Scheduled call: every business ----
    if (cronKey) {
      if (!(await cronKeyIsValid(cronKey))) return fail(401, "Unauthorized");
      const started = Date.now();
      const { data, error } = await admin().from("businesses").select("id");
      if (error) {
        console.error("housekeeping-queue-run: could not list businesses");
        return fail(500, "Something went wrong");
      }
      const totals = emptyTotals();
      let ran = 0;
      let stoppedEarly = false;
      for (const row of data ?? []) {
        if (Date.now() - started > WALL_TIME_MS) {
          stoppedEarly = true; // the next run continues; the work is idempotent
          break;
        }
        const bid = row.id as string;
        try {
          const r = await runForBusiness(bid);
          ran++;
          totals.checkoutTasksCreated += r.checkoutTasksCreated;
          totals.occupiedServiceTasksCreated += r.occupiedServiceTasksCreated;
          totals.skippedAlreadyExisted += r.skippedAlreadyExisted;
          totals.unassignedCount += r.unassignedCount;
          totals.rebalancedCount += r.rebalancedCount;
          totals.overdueCheckoutsNotified += r.overdueCheckoutsNotified;
          totals.cleaningRemindersSent += r.cleaningRemindersSent;
          totals.errorCount += r.errors.length;
          if (r.errors.length > 0) console.error(`housekeeping-queue-run: ${bid} had ${r.errors.length} stage error(s)`);
        } catch (_e) {
          totals.errorCount++;
          console.error(`housekeeping-queue-run: ${bid} failed`);
        }
      }
      return ok({ businesses: ran, stoppedEarly, results: totals });
    }

    // ---- Manual call: the caller's own business ----
    let member;
    try {
      member = await requireMember(req);
    } catch (e) {
      if (e instanceof HttpError && e.status >= 500) throw e;
      return fail(401, "Unauthorized");
    }
    if (member.status !== "active" || !MANUAL_ROLES.includes(member.role)) return fail(401, "Unauthorized");

    const result = await runForBusiness(member.businessId);
    return ok({ ...result });
  } catch (_e) {
    console.error("housekeeping-queue-run: unexpected error");
    return fail(500, "Something went wrong");
  }
});
