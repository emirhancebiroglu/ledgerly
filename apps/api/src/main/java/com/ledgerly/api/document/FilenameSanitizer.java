package com.ledgerly.api.document;

/**
 * Reduces a client-supplied filename to a display-safe name only — never a path. Shared by {@link
 * DocumentUploadService} and {@code com.ledgerly.api.policy.PolicyUploadService}; see {@link
 * ContentHasher} for why this one-method utility is factored out rather than duplicated per
 * upload path.
 */
public final class FilenameSanitizer {

  private FilenameSanitizer() {}

  /**
   * Keeps a display name only. The stored name is never used to build a path — the storage key is
   * the only handle — but a name carrying separators or control characters would still be a hazard
   * for any downstream consumer that renders or re-serves it.
   *
   * @param fallback returned when {@code filename} is null, blank, or reduces to nothing usable
   *     (e.g. {@code "."}, {@code ".."}, or entirely control characters)
   */
  public static String sanitize(String filename, String fallback) {
    if (filename == null || filename.isBlank()) {
      return fallback;
    }
    String withoutPath = filename.replaceAll(".*[/\\\\]", "");
    String cleaned = withoutPath.replaceAll("[\\p{Cntrl}]", "").trim();
    if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
      return fallback;
    }
    return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
  }
}
