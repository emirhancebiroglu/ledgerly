package com.ledgerly.api.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Filesystem-backed {@link StorageClient}, rooted at a configured directory that must live outside
 * any web-served path — nothing in this application serves static files from disk, and the root is
 * never exposed as a URL. Bytes are reachable only through {@link #read(String)}.
 *
 * <p>Keys are minted here as a bare UUID, so two stores of byte-identical content still get
 * distinct keys: a document's identity is the upload event, not its content, and de-duplicating
 * across organizations would let one tenant's key collide with another's.
 */
@Component
public class LocalDiskStorage implements StorageClient {

  /**
   * A key is exactly a canonical lower-case UUID. Anything else — {@code ../}, an absolute path, a
   * separator, a null byte — fails this match, which is why validation can happen without ever
   * touching the filesystem.
   */
  private static final Pattern KEY_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final Path root;

  public LocalDiskStorage(@Value("${ledgerly.storage.root}") Path root) {
    this.root = root.toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.root);
    } catch (IOException e) {
      throw new StorageException("Failed to create storage root at " + this.root, e);
    }
  }

  @Override
  public String store(byte[] content) {
    String key = UUID.randomUUID().toString();
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      // Write to a temp file and move into place so a reader never observes a partial blob.
      Path temp = Files.createTempFile(target.getParent(), key, ".tmp");
      Files.write(temp, content);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StorageException("Failed to store content under key " + key, e);
    }
    return key;
  }

  @Override
  public byte[] read(String key) {
    Path target = resolve(key);
    if (!Files.isRegularFile(target)) {
      throw new StorageKeyNotFoundException("No stored content for key " + key);
    }
    try {
      return Files.readAllBytes(target);
    } catch (IOException e) {
      throw new StorageException("Failed to read content for key " + key, e);
    }
  }

  /**
   * Validates the key shape first, then shards by its first two characters to keep any single
   * directory from growing unbounded. The post-resolution containment check is belt-and-braces: the
   * pattern already makes escaping the root impossible, but the check means a future loosening of
   * the pattern cannot silently become a traversal bug.
   */
  private Path resolve(String key) {
    if (key == null || !KEY_PATTERN.matcher(key).matches()) {
      throw new InvalidStorageKeyException("Malformed storage key");
    }
    Path resolved = root.resolve(key.substring(0, 2)).resolve(key).normalize();
    if (!resolved.startsWith(root)) {
      throw new InvalidStorageKeyException("Malformed storage key");
    }
    return resolved;
  }
}
