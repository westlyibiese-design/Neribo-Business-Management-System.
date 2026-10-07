import { corsHeaders } from "./cors.ts";

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "content-type": "application/json" },
  });
}

export const ok = (extra: Record<string, unknown> = {}) => json(200, { ok: true, ...extra });

export const fail = (status: number, error: string) => json(status, { ok: false, error });
