import { admin } from "./supabaseAdmin.ts";
import { fail } from "./response.ts";

export interface Member {
  userId: string;
  businessId: string;
  role: string;
  status: string;
  name: string;
}

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Reads the Bearer token, verifies it with Supabase Auth and loads the member row (service role). */
export async function requireMember(req: Request): Promise<Member> {
  const header = req.headers.get("authorization") ?? req.headers.get("Authorization") ?? "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) throw new HttpError(401, "Please sign in.");
  const token = match[1].trim();

  const supabase = admin();
  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData?.user) throw new HttpError(401, "Your session has expired. Please sign in again.");

  const { data: row, error: rowError } = await supabase
    .from("business_members")
    .select("user_id, business_id, role, status, name")
    .eq("user_id", userData.user.id)
    .maybeSingle();
  if (rowError) throw new HttpError(500, "Something went wrong");
  if (!row) throw new HttpError(403, "No business account found for this user.");
  if (row.status !== "active") throw new HttpError(403, "This account is suspended.");

  return {
    userId: row.user_id as string,
    businessId: row.business_id as string,
    role: row.role as string,
    status: row.status as string,
    name: row.name as string,
  };
}

export function requireRole(m: Member, roles: string[]): void {
  if (!roles.includes(m.role)) throw new HttpError(403, "Not allowed");
}

export function handleError(e: unknown): Response {
  if (e instanceof HttpError) return fail(e.status, e.message);
  console.error("Unhandled error:", e);
  return fail(500, "Something went wrong");
}
