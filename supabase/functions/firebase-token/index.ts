import { handleOptions } from "../_shared/cors.ts";
import { fail, ok } from "../_shared/response.ts";
import { handleError, requireMember } from "../_shared/auth.ts";
import { admin } from "../_shared/supabaseAdmin.ts";
import { createCustomToken, mirrorMember } from "../_shared/firebase.ts";

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return fail(405, "Use POST.");

  try {
    // 1. Who is calling? (Supabase JWT -> active member row; suspended -> 403)
    const member = await requireMember(req);

    // 2. Extra member fields for the Firestore mirror
    const { data: extra, error: extraError } = await admin()
      .from("business_members")
      .select("email, phone, uses_pin")
      .eq("user_id", member.userId)
      .maybeSingle();
    if (extraError) throw new Error("Could not read member details");
    const usesPin = Boolean(extra?.uses_pin);

    // 3. Firebase work: refresh the mirror, then mint the custom token
    try {
      await mirrorMember(member.businessId, {
        userId: member.userId,
        role: member.role,
        name: member.name,
        email: (extra?.email as string | null | undefined) ?? null,
        phone: (extra?.phone as string | null | undefined) ?? null,
        status: member.status,
        usesPin,
      });

      const token = await createCustomToken(member.userId, {
        biz: member.businessId,
        role: member.role,
        pin: usesPin,
      });

      return ok({ token, businessId: member.businessId, role: member.role });
    } catch (e) {
      // Log the reason only. Never log tokens or keys.
      console.error("firebase-token: Firebase step failed:", e instanceof Error ? e.message : "unknown error");
      return fail(502, "Could not start your live data session. Please try again in a moment.");
    }
  } catch (e) {
    return handleError(e);
  }
});
