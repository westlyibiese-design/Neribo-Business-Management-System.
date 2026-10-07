import { handleOptions } from "../_shared/cors.ts";
import { ok } from "../_shared/response.ts";

Deno.serve((req: Request) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  return ok({ service: "nbms", time: new Date().toISOString() });
});
