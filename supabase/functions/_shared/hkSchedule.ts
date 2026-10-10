// Pure time maths for the housekeeping auto-queue (Phase 24). Imports nothing.

export interface HotelTimeSettings {
  checkOutTime: string;
  housekeepingLeadTimeMinutes: number;
  occupiedStayServiceTime: string;
  occupiedStayServiceEnabled: boolean;
  timezone: string;
}

export const DEFAULT_HOTEL_TIME_SETTINGS: HotelTimeSettings = {
  checkOutTime: "11:00",
  housekeepingLeadTimeMinutes: 60,
  occupiedStayServiceTime: "10:00",
  occupiedStayServiceEnabled: true,
  timezone: "Africa/Lagos",
};

export const REMINDER_INTERVAL_MINUTES = 30;

export function parseHHMM(v: string): { hours: number; minutes: number } {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(v ?? "");
  if (!m) throw new Error(`Invalid time "${v}" — expected 24-hour "HH:MM".`);
  return { hours: Number(m[1]), minutes: Number(m[2]) };
}

function partsInTz(at: Date, tz: string): { y: number; m: number; d: number; h: number; min: number; s: number } {
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    hourCycle: "h23",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
  });
  const get: Record<string, number> = {};
  for (const p of fmt.formatToParts(at)) {
    if (p.type !== "literal") get[p.type] = Number(p.value);
  }
  return { y: get.year, m: get.month, d: get.day, h: get.hour % 24, min: get.minute, s: get.second };
}

/** Minutes the zone is ahead of UTC at this moment (Lagos = 60). */
function tzOffsetMinutes(tz: string, at: Date): number {
  const p = partsInTz(at, tz);
  const wholeSecond = Math.floor(at.getTime() / 1000) * 1000;
  return (Date.UTC(p.y, p.m - 1, p.d, p.h, p.min, p.s) - wholeSecond) / 60000;
}

function ymdToInstant(ymd: { y: number; m: number; d: number }, hhmm: string, tz: string): Date {
  const { hours, minutes } = parseHHMM(hhmm);
  const g = Date.UTC(ymd.y, ymd.m - 1, ymd.d, hours, minutes);
  return new Date(g - tzOffsetMinutes(tz, new Date(g)) * 60000);
}

/** Uses the Date's UTC Y-M-D ("declared calendar day": a booking's check-out day, an assignment's start/end). */
export function calendarDateAtLocalTime(calendarDate: Date, hhmm: string, tz: string): Date {
  return ymdToInstant(
    { y: calendarDate.getUTCFullYear(), m: calendarDate.getUTCMonth() + 1, d: calendarDate.getUTCDate() },
    hhmm,
    tz,
  );
}

/** Uses the instant's Y-M-D as seen in the zone ("today"). */
export function zonedTimeAt(instant: Date, hhmm: string, tz: string): Date {
  const p = partsInTz(instant, tz);
  return ymdToInstant({ y: p.y, m: p.m, d: p.d }, hhmm, tz);
}

/** "yyyy-MM-dd" as seen in the zone. */
export function dateKeyInTimezone(instant: Date, tz: string): string {
  const p = partsInTz(instant, tz);
  return `${String(p.y).padStart(4, "0")}-${String(p.m).padStart(2, "0")}-${String(p.d).padStart(2, "0")}`;
}

export function computeCheckoutTriggerTime(checkOutDate: Date, s: HotelTimeSettings): Date {
  return new Date(
    calendarDateAtLocalTime(checkOutDate, s.checkOutTime, s.timezone).getTime() - s.housekeepingLeadTimeMinutes * 60000,
  );
}

export function computeOccupiedServiceTriggerTime(now: Date, s: HotelTimeSettings): Date {
  return zonedTimeAt(now, s.occupiedStayServiceTime, s.timezone);
}

/** The official check-out moment (no lead time). */
export function computeScheduledCheckoutInstant(checkOutDate: Date, s: HotelTimeSettings): Date {
  return calendarDateAtLocalTime(checkOutDate, s.checkOutTime, s.timezone);
}

export function isDue(trigger: Date, now: Date, windowMinutes = 20): boolean {
  const d = now.getTime() - trigger.getTime();
  return d >= 0 && d < windowMinutes * 60000;
}

export const checkoutTaskId = (bookingId: string): string => `checkout_${bookingId}`;
export const occupiedTaskId = (roomId: string, dateKey: string): string => `occupied_${roomId}_${dateKey}`;

export function isAssignmentActiveOn(a: { startDate: Date; endDate: Date | null }, now: Date, tz: string): boolean {
  const today = zonedTimeAt(now, "00:00", tz);
  const start = calendarDateAtLocalTime(a.startDate, "00:00", tz);
  if (today.getTime() < start.getTime()) return false;
  if (!a.endDate) return true;
  return today.getTime() <= calendarDateAtLocalTime(a.endDate, "00:00", tz).getTime();
}

/** "5 Mar 2026, 11:00" as seen in the business time zone. */
export function formatInZone(at: Date, tz: string): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(at);
  const g: Record<string, string> = {};
  for (const p of parts) g[p.type] = p.value;
  return `${g.day} ${g.month} ${g.year}, ${g.hour}:${g.minute}`;
}

export function formatOverdue(minutes: number): string {
  const m = Math.max(0, Math.floor(minutes));
  const h = Math.floor(m / 60);
  return h > 0 ? `${h}h ${m % 60}m` : `${m}m`;
}
