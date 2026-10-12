// Run with: deno test supabase/functions/media-upload/media.test.ts
import { belongsTo, buildPath, FOLDERS, MAX_BYTES, pathFromPublicUrl, sniffType, TYPES } from "../_shared/media.ts";

function assertEquals<T>(actual: T, expected: T): void {
  if (actual !== expected) {
    throw new Error(`Expected ${JSON.stringify(expected)} but got ${JSON.stringify(actual)}`);
  }
}

const enc = (s: string) => new TextEncoder().encode(s);

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46]);
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d]);
const GIF87 = enc("GIF87a\x01\x00\x01\x00");
const GIF89 = enc("GIF89a\x01\x00\x01\x00");
const WEBP = new Uint8Array([...enc("RIFF"), 1, 2, 3, 4, ...enc("WEBPVP8 ")]);
const AVIF = new Uint8Array([0, 0, 0, 0x1c, ...enc("ftyp"), ...enc("avif"), 0, 0, 0, 0]);
const AVIS = new Uint8Array([0, 0, 0, 0x1c, ...enc("ftyp"), ...enc("avis"), 0, 0, 0, 0]);
const HEIC = new Uint8Array([0, 0, 0, 0x18, ...enc("ftyp"), ...enc("heic"), 0, 0, 0, 0]);

Deno.test("sniffType accepts every allowed type", () => {
  assertEquals(sniffType(JPEG), "image/jpeg");
  assertEquals(sniffType(PNG), "image/png");
  assertEquals(sniffType(GIF87), "image/gif");
  assertEquals(sniffType(GIF89), "image/gif");
  assertEquals(sniffType(WEBP), "image/webp");
  assertEquals(sniffType(AVIF), "image/avif");
  assertEquals(sniffType(AVIS), "image/avif");
});

Deno.test("sniffType rejects text, HEIC, empty and truncated data", () => {
  assertEquals(sniffType(enc("hello, this is not an image")), null);
  assertEquals(sniffType(HEIC), null);
  assertEquals(sniffType(new Uint8Array([])), null);
  assertEquals(sniffType(new Uint8Array([0xff, 0xd8])), null);
});

Deno.test("a PNG sent as image/jpeg is detected as a mismatch", () => {
  const claimed = "image/jpeg";
  assertEquals(sniffType(PNG) === claimed, false);
  assertEquals(sniffType(PNG), "image/png");
});

Deno.test("buildPath has the business/folder/uuid.ext shape", () => {
  assertEquals(
    buildPath("biz-1", "gallery", "png", "11111111-2222-3333-4444-555555555555"),
    "biz-1/gallery/11111111-2222-3333-4444-555555555555.png",
  );
});

Deno.test("folders, types and size limit match the contract", () => {
  assertEquals(FOLDERS.length, 19);
  assertEquals(FOLDERS.includes("cms-login-background"), false);
  assertEquals(FOLDERS.includes("gallery"), true);
  assertEquals(Object.keys(TYPES).length, 5);
  assertEquals(TYPES["image/jpeg"], "jpg");
  assertEquals(MAX_BYTES, 8388608);
});

Deno.test("pathFromPublicUrl: our url, foreign url, empty string", () => {
  const base = "https://abc.supabase.co";
  assertEquals(
    pathFromPublicUrl(`${base}/storage/v1/object/public/nbms-media/biz-1/gallery/a.png`, base),
    "biz-1/gallery/a.png",
  );
  assertEquals(
    pathFromPublicUrl(`${base}/storage/v1/object/public/nbms-media/biz-1/gallery/a.png?v=2`, `${base}/`),
    "biz-1/gallery/a.png",
  );
  assertEquals(pathFromPublicUrl("https://evil.example/storage/v1/object/public/nbms-media/biz-1/a.png", base), null);
  assertEquals(pathFromPublicUrl(`${base}/storage/v1/object/public/other-bucket/biz-1/a.png`, base), null);
  assertEquals(pathFromPublicUrl("", base), null);
});

Deno.test("belongsTo: own path, another business, traversal, double slash", () => {
  assertEquals(belongsTo("biz-1/gallery/a.png", "biz-1"), true);
  assertEquals(belongsTo("biz-2/gallery/a.png", "biz-1"), false);
  assertEquals(belongsTo("biz-10/gallery/a.png", "biz-1"), false);
  assertEquals(belongsTo("biz-1/../biz-2/a.png", "biz-1"), false);
  assertEquals(belongsTo("biz-1//a.png", "biz-1"), false);
  assertEquals(belongsTo("/biz-1/a.png", "biz-1"), false);
  assertEquals(belongsTo("biz-1/", "biz-1"), false);
  assertEquals(belongsTo("", "biz-1"), false);
});
