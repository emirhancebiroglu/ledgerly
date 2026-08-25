package com.ledgerly.api.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Filesystem-backed {@link StorageClient}, rooted at a configured directory that must live outside
 * any web-served path — nothing in this application serves static files from disk, and the root is
 * never exposed as a URL. Bytes are reachable only through {@link #read(String)}.
 *
 * <p>Keys are minted here as a bare UUID, so two stores of byte-identical content still get
 * distinct keys: a document's identity is the upload event, not its content, and de-duplicating
 * across organizations would let one tenant's key collide with another's.
 *
 * <p>Active everywhere except the {@code prod} profile (see {@link R2StorageClient}) — a
 * platform's ephemeral/free-tier disk is fine for local dev and CI, not for a deployment a
 * stranger's uploaded invoice needs to survive a restart.
 */
@Component
@Profile("!prod")
public class LocalDiskStorage implements StorageClient {

  private final Path root;
  private final Supplier<String> keySupplier;
  private final Consumer<Path> afterTempFileCreated;

  @Autowired
  public LocalDiskStorage(@Value("${ledgerly.storage.root}") Path root) {
    this(root, () -> UUID.randomUUID().toString(), temp -> {});
  }

  /**
   * Test-only seam: lets a test pin the minted key (to stage a deterministic filesystem
   * collision) and observe the temp file the instant after it's created (to force a write
   * failure on it, e.g. by making it read-only) — neither is reachable from production code,
   * which always uses the single-argument constructor above.
   */
  LocalDiskStorage(Path root, Supplier<String> keySupplier, Consumer<Path> afterTempFileCreated) {
    this.root = root.toAbsolutePath().normalize();
    this.keySupplier = keySupplier;
    this.afterTempFileCreated = afterTempFileCreated;
    try {
      Files.createDirectories(this.root);
    } catch (IOException e) {
      throw new StorageException("Failed to create storage root at " + this.root, e);
    }
  }

  @Override
  public String store(byte[] content) {
    String key = keySupplier.get();
    Path target = resolve(key);
    Path temp = null;
    try {
      Files.createDirectories(target.getParent());
      // Write to a temp file and move into place so a reader never observes a partial blob.
      temp = Files.createTempFile(target.getParent(), key, ".tmp");
      afterTempFileCreated.accept(temp);
      Files.write(temp, content);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StorageException("Failed to store content under key " + key, e);
    } finally {
      // A successful move already removed the temp file; deleting again is a harmless no-op. A
      // write or move failure otherwise leaves it behind forever, since nothing else ever reaps it.
      if (temp != null) {
        try {
          temp.toFile().setWritable(true);
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // Best-effort cleanup; the original failure is what the caller needs to see.
        }
      }
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

  @Override
  public void delete(String key) {
    Path target = resolve(key);
    try {
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw new StorageException("Failed to delete content for key " + key, e);
    }
  }

  /**
   * Validates the key shape first, then shards by its first two characters to keep any single
   * directory from growing unbounded. The post-resolution containment check is belt-and-braces: the
   * pattern already makes escaping the root impossible, but the check means a future loosening of
   * the pattern cannot silently become a traversal bug.
   */
  private Path resolve(String key) {
    if (!StorageKeys.isValidShape(key)) {
      throw new InvalidStorageKeyException("Malformed storage key");
    }
    Path resolved = root.resolve(key.substring(0, 2)).resolve(key).normalize();
    if (!resolved.startsWith(root)) {
      throw new InvalidStorageKeyException("Malformed storage key");
    }
    return resolved;
  }
}
