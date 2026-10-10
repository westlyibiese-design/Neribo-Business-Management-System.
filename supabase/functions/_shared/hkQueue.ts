// The housekeeping auto-queue generators (Phase 24). One call = one pass for one business. Safe to run twice.
import { admin } from "./supabaseAdmin.ts";
import { fsGet } from "./firebase.ts";
import { fsCreate, fsQuery, fsUpdateFields } from "./fsQuery.ts";
import { serverNotify } from "./serverNotify.ts";
import {
  checkoutTaskId,
  computeCheckoutTriggerTime,
  computeOccupiedServiceTriggerTime,
  computeScheduledCheckoutInstant,
  dateKeyInTimezone,
  DEFAULT_HOTEL_TIME_SETTINGS,
  formatInZone,
  formatOverdue,
  type HotelTimeSettings,
  isAssignmentActiveOn,
  isDue,
  occupiedTaskId,
  parseHHMM,
  REMINDER_INTERVAL_MINUTES,
} from "./hkSchedule.ts";
import { applyLoad, chooseAssignee, computeTaskWeight, type WorkloadEntry } from "./hkBalance.ts";

export interface RunResult {
  ranAt: string;
  checkoutTasksCreated: number;
  occupiedServiceTasksCreated: number;
  skippedAlreadyExisted: number;
  unassignedCount: number;
  rebalancedCount: number;
  overdueCheckoutsNotified: number;
  cleaningRemindersSent: number;
  errors: string[];
}

type Doc = { id: string; data: Record<string, unknown> };

const OVERDUE_RECIPIENT_ROLES = ["receptionist", "manager", "operations_manager", "super_admin"];
const REMINDER_RECIPIENT_ROLES = ["operations_manager", "manager", "super_admin"];
const SUMMARY_RECIPIENT_ROLES = ["operations_manager", "super_admin"];

const str = (v: unknown): string | null => (typeof v === "string" && v.trim() ? v : null);
const asDate = (v: unknown): Date | null => (v instanceof Date && !isNaN(v.getTime()) ? v : null);
const msg = (e: unknown): string => (e instanceof Error ? e.message : String(e));

function pickTime(v: unknown, fallback: string): string {
  const s = str(v);
  if (!s) return fallback;
  try {
    parseHHMM(s);
    return s;
  } catch (_e) {
    return fallback;
  }
}

async function loadSettings(bid: string): Promise<HotelTimeSettings> {
  const d = DEFAULT_HOTEL_TIME_SETTINGS;
  let bizTz: string | null = null;
  try {
    const { data } = await admin().from("businesses").select("timezone").eq("id", bid).maybeSingle();
    bizTz = str(data?.timezone);
  } catch (_e) {
    bizTz = null;
  }
  const raw = (await fsGet(`businesses/${bid}/settings/hotel`)) ?? {};
  const lead = typeof raw.housekeepingLeadTimeMinutes === "number" && raw.housekeepingLeadTimeMinutes >= 0
    ? raw.housekeepingLeadTimeMinutes
    : d.housekeepingLeadTimeMinutes;
  let tz = str(raw.timezone) ?? bizTz ?? d.timezone;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
  } catch (_e) {
    tz = bizTz ?? d.timezone;
  }
  return {
    checkOutTime: pickTime(raw.checkOutTime, d.checkOutTime),
    housekeepingLeadTimeMinutes: lead,
    occupiedStayServiceTime: pickTime(raw.occupiedStayServiceTime, d.occupiedStayServiceTime),
    occupiedStayServiceEnabled: raw.occupiedStayServiceEnabled !== false,
    timezone: tz,
  };
}

export function runQueueMessageUnassigned(n: number): string {
  return `${n} housekeeping task(s) were queued with no housekeeper available (no active room assignment, or the zone owner isn't on shift today with nobody else on duty to cover). Assign them from Room Assignments.`;
}

export async function runForBusiness(bid: string, now: Date = new Date()): Promise<RunResult> {
  const result: RunResult = {
    ranAt: now.toISOString(),
    checkoutTasksCreated: 0,
    occupiedServiceTasksCreated: 0,
    skippedAlreadyExisted: 0,
    unassignedCount: 0,
    rebalancedCount: 0,
    overdueCheckoutsNotified: 0,
    cleaningRemindersSent: 0,
    errors: [],
  };
  const fail = (stage: string, e: unknown) => result.errors.push(`${stage}: ${msg(e)}`);

  // 1. Settings
  let settings: HotelTimeSettings;
  try {
    settings = await loadSettings(bid);
  } catch (e) {
    fail("settings", e);
    settings = { ...DEFAULT_HOTEL_TIME_SETTINGS };
  }
  const tz = settings.timezone;
  const dayKey = dateKeyInTimezone(now, tz);

  // Small caches shared by the stages.
  const roomCache = new Map<string, Record<string, unknown> | null>();
  const getRoom = async (roomId: string) => {
    if (!roomCache.has(roomId)) roomCache.set(roomId, await fsGet(`businesses/${bid}/rooms/${roomId}`));
    return roomCache.get(roomId) ?? null;
  };
  const assignCache = new Map<string, Record<string, unknown> | null>();
  const getAssignment = async (roomId: string) => {
    if (!assignCache.has(roomId)) assignCache.set(roomId, await fsGet(`businesses/${bid}/room_assignments/${roomId}`));
    return assignCache.get(roomId) ?? null;
  };
  let usersCache: Doc[] | null = null;
  const activeUsers = async (): Promise<Doc[]> => {
    if (!usersCache) usersCache = await fsQuery(bid, "users", [{ field: "status", op: "==", value: "active" }], 1000);
    return usersCache;
  };
  const userIdsWithRoles = async (roles: string[]): Promise<string[]> => {
    const ids = new Set<string>();
    for (const u of await activeUsers()) {
      if (roles.includes(str(u.data.role) ?? "")) ids.add(str(u.data.uid) ?? u.id);
    }
    return [...ids];
  };

  // 2. On-duty roster + today's existing workload
  const onDuty: WorkloadEntry[] = [];
  try {
    const shifts = await fsQuery(bid, "shifts", [
      { field: "role", op: "==", value: "housekeeping" },
      { field: "date", op: "==", value: dayKey },
    ]);
    const seen = new Set<string>();
    for (const s of shifts) {
      if (s.data.status !== "scheduled") continue;
      const staffId = str(s.data.staffId);
      if (!staffId || seen.has(staffId)) continue;
      seen.add(staffId);
      onDuty.push({ id: staffId, name: str(s.data.staffName) ?? "Housekeeper", load: 0 });
    }
    const open = await fsQuery(bid, "housekeeping_tasks", [
      { field: "dayKey", op: "==", value: dayKey },
      { field: "status", op: "in", value: ["pending", "in_progress"] },
    ]);
    for (const t of open) {
      const who = str(t.data.assignedTo);
      if (!who) continue;
      const w = typeof t.data.weight === "number"
        ? t.data.weight
        : computeTaskWeight(str(t.data.type) ?? "cleaning", str(t.data.priority) ?? "medium");
      applyLoad(onDuty, who, w);
    }
  } catch (e) {
    fail("roster", e);
  }

  // 3. Checked-in bookings (fetched once, shared)
  let bookings: Doc[] = [];
  try {
    bookings = await fsQuery(bid, "bookings", [{ field: "status", op: "==", value: "checked_in" }]);
  } catch (e) {
    fail("bookings", e);
  }

  const homeOwnerOf = async (roomId: string): Promise<{ id: string; name: string } | null> => {
    const a = await getAssignment(roomId);
    if (!a || a.status !== "active") return null;
    const start = asDate(a.startDate);
    const housekeeperId = str(a.housekeeperId);
    if (!start || !housekeeperId) return null;
    if (!isAssignmentActiveOn({ startDate: start, endDate: asDate(a.endDate) }, now, tz)) return null;
    return { id: housekeeperId, name: str(a.housekeeperName) ?? "Housekeeper" };
  };

  /** Creates one auto task (idempotent) and does the shared bookkeeping. */
  const queueTask = async (p: {
    stage: string;
    taskId: string;
    roomId: string;
    roomNumber: string;
    type: "checkout_cleaning" | "occupied_service";
    priority: "high" | "medium";
    instructions: string | null;
    source: string;
    bookingId: string | null;
    scheduledFor: Date;
    cleanliness: string;
    notifyMessage: (rebalanced: boolean) => string;
    notifySeverity: string;
  }): Promise<void> => {
    const weight = computeTaskWeight(p.type, p.priority);
    const homeOwner = await homeOwnerOf(p.roomId);
    const choice = chooseAssignee({ homeOwner, onDuty, taskWeight: weight });
    const assignee = choice.assignee;
    const created = await fsCreate(bid, "housekeeping_tasks", p.taskId, {
      roomId: p.roomId,
      roomNumber: p.roomNumber,
      type: p.type,
      status: "pending",
      priority: p.priority,
      instructions: p.instructions,
      assignedTo: assignee ? assignee.id : null,
      assignedToName: assignee ? assignee.name : null,
      assignedBy: "system",
      assignedByName: "Automatic Queue",
      scheduledFor: p.scheduledFor,
      bookingId: p.bookingId,
      source: p.source,
      dayKey,
      weight,
      homeOwnerId: homeOwner ? homeOwner.id : null,
      homeOwnerName: homeOwner ? homeOwner.name : null,
      rebalanced: choice.rebalanced,
      createdAt: now,
      updatedAt: now,
      startedAt: null,
      completedAt: null,
      completedBy: null,
      completedByName: null,
      isDeleted: false,
    });
    if (!created) {
      result.skippedAlreadyExisted++;
      return;
    }
    if (p.type === "checkout_cleaning") result.checkoutTasksCreated++;
    else result.occupiedServiceTasksCreated++;
    await fsUpdateFields(bid, `rooms/${p.roomId}`, { cleanliness: p.cleanliness, cleanlinessUpdatedAt: now });
    if (assignee) {
      applyLoad(onDuty, assignee.id, weight);
      if (choice.rebalanced) result.rebalancedCount++;
      try {
        await serverNotify(bid, {
          type: "housekeeping_task_scheduled",
          title: "New Cleaning Task",
          message: p.notifyMessage(choice.rebalanced),
          forUserIds: [assignee.id],
          severity: p.notifySeverity,
          link: "/admin/housekeeping",
        });
      } catch (e) {
        fail(p.stage, e);
      }
    } else {
      result.unassignedCount++;
    }
  };

  // 4. Checkout-cleaning tasks
  try {
    for (const b of bookings) {
      try {
        const checkOut = asDate(b.data.checkOut);
        const roomId = str(b.data.roomId);
        if (!checkOut || !roomId) continue;
        const trigger = computeCheckoutTriggerTime(checkOut, settings);
        if (!isDue(trigger, now)) continue;
        const room = await getRoom(roomId);
        const roomNumber = str(room?.number) ?? roomId;
        await queueTask({
          stage: "checkoutTasks",
          taskId: checkoutTaskId(b.id),
          roomId,
          roomNumber,
          type: "checkout_cleaning",
          priority: "high",
          instructions: null,
          source: "auto_checkout",
          bookingId: b.id,
          scheduledFor: trigger,
          cleanliness: "checkout_cleaning_due",
          notifySeverity: "warning",
          notifyMessage: (rb) =>
            `Room ${roomNumber} needs check-out cleaning (high priority) — guest departs soon.` +
            (rb ? " Reassigned today to keep workloads balanced." : ""),
        });
      } catch (e) {
        fail("checkoutTasks", e);
      }
    }
  } catch (e) {
    fail("checkoutTasks", e);
  }

  // 5. Occupied-service tasks
  try {
    if (settings.occupiedStayServiceEnabled) {
      const trigger = computeOccupiedServiceTriggerTime(now, settings);
      if (isDue(trigger, now)) {
        const doneRooms = new Set<string>();
        for (const b of bookings) {
          try {
            const roomId = str(b.data.roomId);
            if (!roomId || doneRooms.has(roomId)) continue;
            doneRooms.add(roomId);
            const co = await fsGet(`businesses/${bid}/housekeeping_tasks/${checkoutTaskId(b.id)}`);
            if (co && co.status !== "completed" && co.status !== "skipped") continue;
            const room = await getRoom(roomId);
            const roomNumber = str(room?.number) ?? roomId;
            await queueTask({
              stage: "occupiedTasks",
              taskId: occupiedTaskId(roomId, dayKey),
              roomId,
              roomNumber,
              type: "occupied_service",
              priority: "medium",
              instructions: "Guest is in-house — service the room without disturbing personal belongings.",
              source: "auto_occupied_stay",
              bookingId: b.id,
              scheduledFor: trigger,
              cleanliness: "daily_cleaning_due",
              notifySeverity: "info",
              notifyMessage: (rb) =>
                `Room ${roomNumber} is due for its daily occupied-room service.` +
                (rb ? " Assigned to you today to keep workloads balanced." : ""),
            });
          } catch (e) {
            fail("occupiedTasks", e);
          }
        }
      }
    }
  } catch (e) {
    fail("occupiedTasks", e);
  }

  // 6. Overdue checkouts
  try {
    for (const b of bookings) {
      try {
        const checkOut = asDate(b.data.checkOut);
        const roomId = str(b.data.roomId);
        if (!checkOut || !roomId) continue;
        const scheduled = computeScheduledCheckoutInstant(checkOut, settings);
        if (now.getTime() <= scheduled.getTime()) continue;
        const room = await getRoom(roomId);
        if (room && room.checkoutOverdue !== true) {
          await fsUpdateFields(bid, `rooms/${roomId}`, { checkoutOverdue: true });
        }
        const last = asDate(b.data.lastOverdueNotifiedAt);
        if (last && now.getTime() - last.getTime() < REMINDER_INTERVAL_MINUTES * 60000) continue;
        const recipients = await userIdsWithRoles(OVERDUE_RECIPIENT_ROLES);
        if (recipients.length === 0) continue;
        const roomNumber = str(room?.number) ?? str(b.data.roomNumber) ?? roomId;
        const overdue = formatOverdue((now.getTime() - scheduled.getTime()) / 60000);
        await serverNotify(bid, {
          type: "checkout_overdue",
          title: "Checkout Overdue",
          severity: "critical",
          link: "/admin/check-out",
          forUserIds: recipients,
          message:
            `${str(b.data.guestName) ?? "Guest"} in Room ${roomNumber} was scheduled to check out at ${formatInZone(scheduled, tz)} and is still checked in — ${overdue} overdue. Please process the check-out or confirm an extended stay.`,
        });
        await fsUpdateFields(bid, `bookings/${b.id}`, { lastOverdueNotifiedAt: now });
        result.overdueCheckoutsNotified++;
      } catch (e) {
        fail("overdueCheckouts", e);
      }
    }
  } catch (e) {
    fail("overdueCheckouts", e);
  }

  // 7. Cleaning reminders
  try {
    const cleaning = await fsQuery(bid, "rooms", [{ field: "status", op: "==", value: "cleaning" }]);
    for (const r of cleaning) {
      try {
        const last = asDate(r.data.cleaningReminderLastSentAt);
        if (last && now.getTime() - last.getTime() < REMINDER_INTERVAL_MINUTES * 60000) continue;
        const recipients = new Set(await userIdsWithRoles(REMINDER_RECIPIENT_ROLES));
        const a = await getAssignment(r.id);
        const hk = a && a.status === "active" ? str(a.housekeeperId) : null;
        if (hk) {
          const users = await activeUsers();
          if (users.some((u) => (str(u.data.uid) ?? u.id) === hk)) recipients.add(hk);
        }
        if (recipients.size === 0) continue;
        const roomNumber = str(r.data.number) ?? r.id;
        await serverNotify(bid, {
          type: "checkout_cleaning_pending",
          title: "Room Awaiting Checkout Cleaning",
          severity: "warning",
          link: "/admin/housekeeping",
          forUserIds: [...recipients],
          message: `Room ${roomNumber} has been vacated and is still waiting on checkout cleaning.`,
        });
        await fsUpdateFields(bid, `rooms/${r.id}`, { cleaningReminderLastSentAt: now });
        result.cleaningRemindersSent++;
      } catch (e) {
        fail("cleaningReminders", e);
      }
    }
  } catch (e) {
    fail("cleaningReminders", e);
  }

  // 8. Unassigned summary
  try {
    if (result.unassignedCount > 0) {
      const recipients = await userIdsWithRoles(SUMMARY_RECIPIENT_ROLES);
      await serverNotify(bid, {
        type: "housekeeping_task_scheduled",
        title: "Unassigned Housekeeping Tasks",
        severity: "warning",
        link: "/admin/housekeeping/assignments",
        forUserIds: recipients,
        message: runQueueMessageUnassigned(result.unassignedCount),
      });
    }
  } catch (e) {
    fail("unassignedSummary", e);
  }

  return result;
}
