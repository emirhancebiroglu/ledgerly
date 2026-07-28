import { afterEach, describe, expect, it, vi } from "vitest";
import { uploadDocument } from "@/lib/document-upload";

describe("uploadDocument", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("sends the Idempotency-Key header and returns ok:true on success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "doc-1",
          filename: "receipt.pdf",
          contentType: "application/pdf",
          sizeBytes: 1000,
          status: "PENDING",
          failureReason: null,
        }),
        { status: 201 },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const file = new File(["%PDF-1.4"], "receipt.pdf", { type: "application/pdf" });
    const result = await uploadDocument(file, "key-123");

    expect(result).toEqual({
      ok: true,
      document: {
        id: "doc-1",
        filename: "receipt.pdf",
        contentType: "application/pdf",
        sizeBytes: 1000,
        status: "PENDING",
        failureReason: null,
      },
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/documents");
    expect((init.headers as Record<string, string>)["Idempotency-Key"]).toBe("key-123");
    expect(init.body).toBeInstanceOf(FormData);
  });

  it("returns ok:false with the server's message on a 415 (unsupported type)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ detail: "Unsupported document type; expected PDF, JPEG or PNG" }),
          { status: 415 },
        ),
      ),
    );

    const file = new File(["not a real doc"], "malware.exe", { type: "application/x-msdownload" });
    const result = await uploadDocument(file, "key-456");

    expect(result).toEqual({
      ok: false,
      status: 415,
      message: "Unsupported document type; expected PDF, JPEG or PNG",
    });
  });

  it("returns ok:false with a generic message when the error body isn't JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("boom", { status: 500 })));

    const file = new File(["x"], "x.pdf", { type: "application/pdf" });
    const result = await uploadDocument(file, "key-789");

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.message).toMatch(/Upload failed/);
    }
  });
});
