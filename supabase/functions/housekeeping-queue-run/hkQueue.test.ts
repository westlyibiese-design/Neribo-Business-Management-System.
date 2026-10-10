// Run with: deno test supabase/functions/housekeeping-queue-run/
import {
  calendarDateAtLocalTime,
  checkoutTaskId,
  computeCheckoutTriggerTime,
  computeOccupiedServiceTriggerTime,
  computeScheduledCheckoutInstant,
  dateKeyInTimezone,
  DEFAULT_HOTEL_TIME_SETTINGS,
  formatInZone,
  formatOverdue,
  isAssignmentActiveOn,
  isDue,
  occupiedTaskId,
  parseHHMM,
  zonedTimeAt,
} from "../_shared/hkSchedule.ts";
import { applyLoad, chooseAssignee, computeTaskWeight } from "../_shared/hkBalance.ts";
import { buildStructuredQuery } from "../_shared/fsQuery.ts";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}
function same(actual: unknown, expected: unknown, label: string): void {
  if (actual !== expected) throw new Error(`${label}: expected ${String(expected)} but got ${String(actual)}`);
}

const lagos = { ...DEFAULT_HOTEL_TIME_SETTINGS };
const newYork = { ...DEFAULT_HOTEL_TIME_SETTINGS, timezone: "America/New_York" };
const checkOutDay = new Date(Date.UTC(2026, 2, 5)); // declared calendar day: 5 March 2026

Deno.test("parseHHMM accepts HH:MM and rejects the rest", () => {
  same(parseHHMM("07:05").hours, 7, "hours");
  same(parseHHMM("07:05").minutes, 5, "minutes");
  let threw = false;
  try {
    parseHHMM("7:5");
  } catch (e) {
    threw = (e as Error).message === 'Invalid time "7:5" — expected 24-hour "HH:MM".';
  }
  check(threw, "bad time must throw the exact message");
});

Deno.test("isDue window edges", () => {
  const t = new Date("2026-03-05T09:00:00Z");
  check(isDue(t, new Date(t.getTime())), "exactly at the trigger is due");
  check(!isDue(t, new Date(t.getTime() - 1)), "1 ms before is not due");
  check(isDue(t, new Date(t.getTime() + 20 * 60000 - 1)), "just inside 20 minutes is due");
  check(!isDue(t, new Date(t.getTime() + 20 * 60000)), "exactly 20 minutes is not due");
  check(isDue(t, new Date(t.getTime() + 29 * 60000), 30), "custom window is respected");
});

Deno.test("computeCheckoutTriggerTime Africa/Lagos: 11:00 minus 60 min = 10:00 local = 09:00 UTC", () => {
  same(computeCheckoutTriggerTime(checkOutDay, lagos).toISOString(), "2026-03-05T09:00:00.000Z", "lagos trigger");
  same(computeScheduledCheckoutInstant(checkOutDay, lagos).toISOString(), "2026-03-05T10:00:00.000Z", "lagos official time");
});

Deno.test("computeCheckoutTriggerTime for a zone west of UTC (America/New_York, winter)", () => {
  same(computeCheckoutTriggerTime(checkOutDay, newYork).toISOString(), "2026-03-05T15:00:00.000Z", "new york trigger");
});

Deno.test("dateKeyInTimezone across midnight", () => {
  const at = new Date("2026-03-05T23:30:00Z");
  same(dateKeyInTimezone(at, "Africa/Lagos"), "2026-03-06", "lagos is already tomorrow");
  same(dateKeyInTimezone(at, "America/New_York"), "2026-03-05", "new york is still today");
  same(dateKeyInTimezone(new Date("2026-03-06T04:30:00Z"), "America/New_York"), "2026-03-05", "ny before 05:00Z");
});

Deno.test("occupied-service trigger uses today's date in the business zone", () => {
  const now = new Date("2026-03-05T23:30:00Z"); // 00:30 on 6 March in Lagos
  same(computeOccupiedServiceTriggerTime(now, lagos).toISOString(), "2026-03-06T09:00:00.000Z", "lagos 10:00 on the 6th");
  same(zonedTimeAt(now, "00:00", "Africa/Lagos").toISOString(), "2026-03-05T23:00:00.000Z", "lagos midnight");
  same(calendarDateAtLocalTime(checkOutDay, "00:00", "Africa/Lagos").toISOString(), "2026-03-04T23:00:00.000Z", "declared day midnight");
});

Deno.test("isAssignmentActiveOn", () => {
  const now = new Date("2026-03-05T10:00:00Z");
  const day = (d: number) => new Date(Date.UTC(2026, 2, d));
  check(isAssignmentActiveOn({ startDate: day(5), endDate: null }, now, "Africa/Lagos"), "starts today, open ended");
  check(!isAssignmentActiveOn({ startDate: day(6), endDate: null }, now, "Africa/Lagos"), "starts tomorrow");
  check(isAssignmentActiveOn({ startDate: day(1), endDate: day(5) }, now, "Africa/Lagos"), "ends today still active");
  check(!isAssignmentActiveOn({ startDate: day(1), endDate: day(4) }, now, "Africa/Lagos"), "ended yesterday");
});

Deno.test("task id formats", () => {
  same(checkoutTaskId("abc123"), "checkout_abc123", "checkout id");
  same(occupiedTaskId("room9", "2026-03-05"), "occupied_room9_2026-03-05", "occupied id");
});

Deno.test("message formatting", () => {
  same(formatOverdue(45), "45m", "under an hour");
  same(formatOverdue(135.9), "2h 15m", "hours and minutes");
  same(formatInZone(new Date("2026-03-05T10:00:00Z"), "Africa/Lagos"), "5 Mar 2026, 11:00", "business-zone format");
});

Deno.test("task weights", () => {
  same(computeTaskWeight("checkout_cleaning", "high"), 2.5, "checkout high");
  same(computeTaskWeight("occupied_service", "medium"), 1, "occupied medium");
  same(computeTaskWeight("maintenance_followup", "urgent"), 2.5, "followup urgent");
  same(computeTaskWeight("unknown_type", "unknown_priority"), 1, "fallbacks");
});

Deno.test("chooseAssignee gives each of the five reasons", () => {
  const owner = { id: "a", name: "Ada" };
  same(chooseAssignee({ homeOwner: null, onDuty: [{ id: "a", name: "Ada", load: 0 }], taskWeight: 2 }).reason, "no_zone", "no_zone");
  same(chooseAssignee({ homeOwner: owner, onDuty: [], taskWeight: 2 }).reason, "no_one_on_duty", "no_one_on_duty");
  const home = chooseAssignee({ homeOwner: owner, onDuty: [{ id: "a", name: "Ada", load: 1 }], taskWeight: 2 });
  same(home.reason, "home_owner", "home_owner");
  same(home.assignee?.id, "a", "home owner keeps it");
  const off = chooseAssignee({ homeOwner: owner, onDuty: [{ id: "b", name: "Bola", load: 3 }, { id: "c", name: "Chi", load: 1 }], taskWeight: 2 });
  same(off.reason, "home_owner_off_duty", "home_owner_off_duty");
  same(off.assignee?.id, "c", "least loaded covers");
  check(off.rebalanced, "off duty counts as rebalanced");
  const over = chooseAssignee({ homeOwner: owner, onDuty: [{ id: "a", name: "Ada", load: 6 }, { id: "b", name: "Bola", load: 1 }], taskWeight: 2 });
  same(over.reason, "rebalanced_overloaded", "rebalanced_overloaded");
  same(over.assignee?.id, "b", "lighter person takes it");
});

Deno.test("chooseAssignee: equal loads break ties by name; nobody lighter keeps it with the owner", () => {
  const owner = { id: "a", name: "Ada" };
  const even = chooseAssignee({ homeOwner: owner, onDuty: [{ id: "a", name: "Ada", load: 4 }, { id: "b", name: "Bola", load: 4 }], taskWeight: 2 });
  same(even.reason, "home_owner", "within threshold");
  const zero = chooseAssignee({ homeOwner: owner, onDuty: [{ id: "a", name: "Ada", load: 0 }, { id: "b", name: "Bola", load: 0 }], taskWeight: 2 });
  same(zero.reason, "home_owner", "average zero keeps home owner");
});

Deno.test("applyLoad adds in place and ignores strangers", () => {
  const roster = [{ id: "a", name: "Ada", load: 1 }, { id: "b", name: "Bola", load: 0 }];
  applyLoad(roster, "a", 2.5);
  applyLoad(roster, "nobody", 9);
  same(roster[0].load, 3.5, "a load");
  same(roster[1].load, 0, "b load");
});

Deno.test("Firestore query body", () => {
  const one = buildStructuredQuery("bookings", [{ field: "status", op: "==", value: "checked_in" }], 500) as Record<string, any>;
  same(one.where.fieldFilter.op, "EQUAL", "single filter op");
  same(one.from[0].collectionId, "bookings", "collection");
  const two = buildStructuredQuery("housekeeping_tasks", [
    { field: "dayKey", op: "==", value: "2026-03-05" },
    { field: "status", op: "in", value: ["pending", "in_progress"] },
  ], 10) as Record<string, any>;
  same(two.where.compositeFilter.op, "AND", "composite");
  same(two.where.compositeFilter.filters[1].fieldFilter.op, "IN", "in op");
  same(two.where.compositeFilter.filters[1].fieldFilter.value.arrayValue.values.length, 2, "in values");
  same(two.limit, 10, "limit");
});
