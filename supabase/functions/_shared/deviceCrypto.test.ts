// Run with: deno test supabase/functions/_shared/deviceCrypto.test.ts
import { hashDevicePin, isValidDevicePinFormat, verifyDevicePin } from "./deviceCrypto.ts";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

Deno.test("hash then verify returns true", async () => {
  const stored = await hashDevicePin("123456");
  check(stored.startsWith("pbkdf2$90000$"), "unexpected format: " + stored);
  check(stored.split("$").length === 4, "should have 4 parts");
  check(await verifyDevicePin("123456", stored), "correct PIN should verify");
});

Deno.test("wrong PIN returns false", async () => {
  const stored = await hashDevicePin("123456");
  check(!(await verifyDevicePin("654321", stored)), "wrong PIN must not verify");
  check(!(await verifyDevicePin("123456", "garbage")), "malformed hash must not verify");
});

Deno.test("two hashes of the same PIN differ (random salt)", async () => {
  const a = await hashDevicePin("1234567890");
  const b = await hashDevicePin("1234567890");
  check(a !== b, "salts should differ");
});

Deno.test("PIN format check", () => {
  check(!isValidDevicePinFormat("12345"), "5 digits invalid");
  check(isValidDevicePinFormat("123456"), "6 digits valid");
  check(isValidDevicePinFormat("1234567890"), "10 digits valid");
  check(!isValidDevicePinFormat("12345678901"), "11 digits invalid");
  check(!isValidDevicePinFormat("12345a"), "letters invalid");
});
