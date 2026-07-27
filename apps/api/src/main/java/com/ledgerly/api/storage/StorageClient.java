package com.ledgerly.api.storage;

/**
 * Port for opaque blob storage. Callers never choose the key — {@link #store} mints one and hands
 * it back, so a caller cannot influence where bytes land on disk (or in a bucket).
 *
 * <p>The only implementation at M4 is {@link LocalDiskStorage}. An S3-backed adapter is a new
 * implementation of this same interface (see the 2026-07-27 ADR on object storage); no caller
 * changes when it arrives.
 */
public interface StorageClient {

  /**
   * Stores the given bytes under a freshly minted opaque key.
   *
   * @return the key, the only handle by which the content can be read back
   */
  String store(byte[] content);

  /**
   * Reads back previously stored content.
   *
   * @throws StorageKeyNotFoundException if no content exists under the key
   * @throws InvalidStorageKeyException if the key is not one this store could have minted
   */
  byte[] read(String key);

  /**
   * Deletes previously stored content. A no-op, not an error, if nothing exists under the key —
   * callers use this for best-effort cleanup (e.g. after a rolled-back transaction) where the
   * blob may never have been reachable in the first place.
   *
   * @throws InvalidStorageKeyException if the key is not one this store could have minted
   */
  void delete(String key);
}
