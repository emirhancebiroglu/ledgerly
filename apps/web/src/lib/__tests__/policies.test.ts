import { describe, expect, it } from "vitest";
import {
  parsePolicyChunk,
  parsePolicyChunks,
  parsePolicyDocument,
  parsePolicyDocuments,
} from "@/lib/policies";

describe("parsePolicyDocument", () => {
  it("parses an EMBEDDED document with a chunk count", () => {
    const result = parsePolicyDocument({
      id: "doc-1",
      filename: "expense-policy-2026.pdf",
      status: "EMBEDDED",
      failureReason: null,
      createdAt: "2026-08-12T00:00:00Z",
      chunkCount: 12,
    });

    expect(result).toEqual({
      id: "doc-1",
      filename: "expense-policy-2026.pdf",
      status: "EMBEDDED",
      failureReason: null,
      createdAt: "2026-08-12T00:00:00Z",
      chunkCount: 12,
    });
  });

  it("parses a FAILED document carrying failureReason", () => {
    const result = parsePolicyDocument({
      id: "doc-2",
      filename: "procurement-handbook-v4.pdf",
      status: "FAILED",
      failureReason: "pdf_text_extraction_empty: no text layer found",
      createdAt: "2026-07-28T00:00:00Z",
      chunkCount: 0,
    });

    expect(result).toEqual({
      id: "doc-2",
      filename: "procurement-handbook-v4.pdf",
      status: "FAILED",
      failureReason: "pdf_text_extraction_empty: no text layer found",
      createdAt: "2026-07-28T00:00:00Z",
      chunkCount: 0,
    });
  });

  it("parses without producing the string 'undefined' when failureReason is absent", () => {
    const result = parsePolicyDocument({
      id: "doc-3",
      filename: "supplier-code-of-conduct.pdf",
      status: "PROCESSING",
      createdAt: "2026-08-22T00:00:00Z",
    });

    expect("error" in result).toBe(false);
    if (!("error" in result)) {
      expect(result.failureReason).toBeNull();
      expect(result.chunkCount).toBe(0);
      expect(JSON.stringify(result)).not.toContain("undefined");
    }
  });

  it("surfaces an unknown status as a typed parse failure rather than rendering it raw", () => {
    const result = parsePolicyDocument({
      id: "doc-4",
      filename: "x.pdf",
      status: "ARCHIVED",
      createdAt: "2026-08-22T00:00:00Z",
    });

    expect("error" in result).toBe(true);
  });

  it("fails on a missing id or filename", () => {
    expect(
      "error" in
        parsePolicyDocument({ filename: "x.pdf", status: "PENDING", createdAt: "2026-08-22T00:00:00Z" }),
    ).toBe(true);
  });
});

describe("parsePolicyDocuments", () => {
  it("parses a list of mixed-status documents", () => {
    const result = parsePolicyDocuments([
      { id: "1", filename: "a.pdf", status: "EMBEDDED", createdAt: "t", chunkCount: 3 },
      { id: "2", filename: "b.pdf", status: "FAILED", createdAt: "t", failureReason: "x", chunkCount: 0 },
    ]);

    expect("error" in result).toBe(false);
    if (!("error" in result)) {
      expect(result).toHaveLength(2);
    }
  });

  it("fails the whole list when one entry is malformed", () => {
    const result = parsePolicyDocuments([
      { id: "1", filename: "a.pdf", status: "EMBEDDED", createdAt: "t", chunkCount: 3 },
      { id: "2" },
    ]);

    expect("error" in result).toBe(true);
  });

  it("fails on a non-array payload", () => {
    expect("error" in parsePolicyDocuments({ not: "an array" })).toBe(true);
  });
});

describe("parsePolicyChunk / parsePolicyChunks", () => {
  it("parses a chunk with index and text", () => {
    const result = parsePolicyChunk({ index: 0, text: "Travel over 500 EUR needs approval." });
    expect(result).toEqual({ index: 0, text: "Travel over 500 EUR needs approval." });
  });

  it("fails on a missing text field", () => {
    expect("error" in parsePolicyChunk({ index: 0 })).toBe(true);
  });

  it("parses an ordered list of chunks", () => {
    const result = parsePolicyChunks([
      { index: 0, text: "First." },
      { index: 1, text: "Second." },
    ]);
    expect("error" in result).toBe(false);
    if (!("error" in result)) {
      expect(result.map((c) => c.index)).toEqual([0, 1]);
    }
  });

  it("fails on a non-array payload", () => {
    expect("error" in parsePolicyChunks(null)).toBe(true);
  });
});
