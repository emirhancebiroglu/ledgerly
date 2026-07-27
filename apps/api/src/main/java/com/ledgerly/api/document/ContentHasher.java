package com.ledgerly.api.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of uploaded bytes, as lowercase hex. Shared by {@link DocumentUploadService} and {@code
 * com.ledgerly.api.policy.PolicyUploadService} — both stamp an upload with its content hash before
 * storing it, and duplicating this one-method utility across packages was exactly the kind of
 * drift the M5.1 backlog note about splitting {@code DocumentUploadService} flagged.
 */
public final class ContentHasher {

  private ContentHasher() {}

  public static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }
}
