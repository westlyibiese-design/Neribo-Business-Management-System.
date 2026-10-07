// Writes an audit-log entry from the server side (Firestore: businesses/{bid}/audit_logs).
// Never throws: a failed audit write must not break the action that caused it.
import { fsAdd } from "./firebase.ts";

export async function logServerAction(
  businessId: string,
  userId: string,
  userName: string,
  userRole: string,
  action: string,
  collection: string,
  documentId: string,
  previousValue: Record<string, unknown> | null,
  newValue: Record<string, unknown> | null,
): Promise<void> {
  try {
    await fsAdd(`businesses/${businessId}/audit_logs`, {
      userId,
      userName,
      userRole,
      action,
      collection,
      documentId,
      previousValue,
      newValue,
      deviceInfo: "server",
      timestamp: new Date(),
      isDeleted: false,
    });
  } catch (e) {
    console.error("serverAudit: could not write audit log:", e instanceof Error ? e.message : "unknown");
  }
}
