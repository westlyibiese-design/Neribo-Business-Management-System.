// Pure workload balancing for the housekeeping auto-queue (Phase 24). Same numbers as the Android file of Phase 23.
// Imports nothing.

export const TASK_TYPE_WEIGHT: Record<string, number> = {
  checkout_cleaning: 2,
  occupied_service: 1,
  cleaning: 1,
  manual: 1,
  maintenance_followup: 1.5,
};
export const PRIORITY_WEIGHT_BONUS: Record<string, number> = { urgent: 1, high: 0.5, medium: 0, low: 0 };

export const computeTaskWeight = (type: string, priority: string): number =>
  (TASK_TYPE_WEIGHT[type] ?? 1) + (PRIORITY_WEIGHT_BONUS[priority] ?? 0);

export const DEFAULT_REBALANCE_THRESHOLD = 0.25;

export interface WorkloadEntry {
  id: string;
  name: string;
  load: number;
}

export type AssignReason = "no_zone" | "no_one_on_duty" | "home_owner" | "home_owner_off_duty" | "rebalanced_overloaded";

export function chooseAssignee(
  { homeOwner, onDuty, taskWeight, thresholdRatio = DEFAULT_REBALANCE_THRESHOLD }: {
    homeOwner: { id: string; name: string } | null;
    onDuty: WorkloadEntry[];
    taskWeight: number;
    thresholdRatio?: number;
  },
): { assignee: { id: string; name: string } | null; rebalanced: boolean; reason: AssignReason } {
  if (!homeOwner) return { assignee: null, rebalanced: false, reason: "no_zone" };
  if (onDuty.length === 0) return { assignee: null, rebalanced: false, reason: "no_one_on_duty" };
  const leastLoaded = () => [...onDuty].sort((a, b) => a.load - b.load || a.name.localeCompare(b.name))[0];
  const home = onDuty.find((o) => o.id === homeOwner.id);
  if (!home) {
    const p = leastLoaded();
    return { assignee: { id: p.id, name: p.name }, rebalanced: true, reason: "home_owner_off_duty" };
  }
  const avg = onDuty.reduce((s, o) => s + o.load, 0) / onDuty.length;
  if (onDuty.length === 1 || avg === 0 || home.load + taskWeight <= avg * (1 + thresholdRatio)) {
    return { assignee: homeOwner, rebalanced: false, reason: "home_owner" };
  }
  const p = leastLoaded();
  if (p.id === homeOwner.id) return { assignee: homeOwner, rebalanced: false, reason: "home_owner" };
  return { assignee: { id: p.id, name: p.name }, rebalanced: true, reason: "rebalanced_overloaded" };
}

/** Adds this weight to that person's load, in place. */
export function applyLoad(onDuty: WorkloadEntry[], id: string, weight: number): void {
  const entry = onDuty.find((o) => o.id === id);
  if (entry) entry.load += weight;
}
