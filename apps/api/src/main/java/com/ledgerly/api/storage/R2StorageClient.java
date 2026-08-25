package com.ledgerly.api.storage;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Cloudflare R2 (S3-compatible) backed {@link StorageClient} — the {@code prod} profile's
 * durable alternative to {@link LocalDiskStorage}, since Render's web-service disk does not
 * survive a restart or redeploy (see the 2026-08-25 ADR on object storage).
 *
 * <p>Key contract mirrors {@link LocalDiskStorage} exactly: a bare, caller-opaque UUID, so
 * either implementation is a drop-in swap behind {@link StorageClient} — no caller, and no
 * previously stored key, changes meaning when the backend does.
 */
@Component
@Profile("prod")
public class R2StorageClient implements StorageClient {

  private final S3Client s3Client;
  private final String bucket;

  public R2StorageClient(S3Client s3Client, @Value("${ledgerly.storage.r2.bucket}") String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  @Override
  public String store(byte[] content) {
    String key = UUID.randomUUID().toString();
    try {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw new StorageException("Failed to store content under key " + key, e);
    }
    return key;
  }

  @Override
  public byte[] read(String key) {
    validate(key);
    try {
      return s3Client
          .getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
          .readAllBytes();
    } catch (NoSuchKeyException e) {
      throw new StorageKeyNotFoundException("No stored content for key " + key);
    } catch (SdkException | java.io.IOException e) {
      throw new StorageException("Failed to read content for key " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    validate(key);
    try {
      // R2 (like S3) treats deleting a missing key as success, matching StorageClient's
      // no-op-not-an-error contract for a key that was never reachable in the first place.
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (SdkException e) {
      throw new StorageException("Failed to delete content for key " + key, e);
    }
  }

  private static void validate(String key) {
    if (!StorageKeys.isValidShape(key)) {
      throw new InvalidStorageKeyException("Malformed storage key");
    }
  }
}
