package com.ledgerly.api.document;

import java.util.Arrays;
import java.util.Optional;

/**
 * Media types the upload endpoint accepts, identified by their leading bytes.
 *
 * <p>A filename extension and a client-supplied {@code Content-Type} are both attacker-controlled;
 * neither is consulted. The bytes are the only evidence, which is what stops a {@code .pdf}-named
 * executable or HTML file from being stored and later served back as a trusted document
 * (OWASP A01/A05).
 */
public enum DetectedContentType {
  PDF("application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46}),
  JPEG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
  PNG(
      "image/png",
      new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

  private final String mediaType;
  private final byte[] magicBytes;

  DetectedContentType(String mediaType, byte[] magicBytes) {
    this.mediaType = mediaType;
    this.magicBytes = magicBytes;
  }

  public String mediaType() {
    return mediaType;
  }

  /** Identifies {@code content} by its leading bytes, or empty if it is not a supported type. */
  public static Optional<DetectedContentType> detect(byte[] content) {
    if (content == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(candidate -> candidate.matches(content)).findFirst();
  }

  private boolean matches(byte[] content) {
    if (content.length < magicBytes.length) {
      return false;
    }
    return Arrays.equals(content, 0, magicBytes.length, magicBytes, 0, magicBytes.length);
  }
}
