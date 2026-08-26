package com.ledgerly.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class R2StorageClientTest {

  private static final String BUCKET = "ledgerly-documents";

  private S3Client s3Client;
  private R2StorageClient storage;

  @BeforeEach
  void setUp() {
    s3Client = mock(S3Client.class);
    storage = new R2StorageClient(s3Client, BUCKET);
  }

  @Test
  void storesUnderAFreshlyMintedUuidKeyInTheConfiguredBucket() {
    byte[] content = "invoice bytes".getBytes(StandardCharsets.UTF_8);

    String key = storage.store(content);

    assertThat(key).matches("^[0-9a-f-]{36}$");
    var captor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(captor.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
    assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(captor.getValue().key()).isEqualTo(key);
  }

  @Test
  void readsBackPreviouslyStoredContentByKey() {
    String key = UUID.randomUUID().toString();
    byte[] content = "invoice bytes".getBytes(StandardCharsets.UTF_8);
    var stream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(content)));
    when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);

    byte[] result = storage.read(key);

    assertThat(result).isEqualTo(content);
    var captor = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client).getObject(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(captor.getValue().key()).isEqualTo(key);
  }

  @Test
  void readingAnUnknownKeyRaisesNotFoundRatherThanPropagatingTheSdkException() {
    String key = UUID.randomUUID().toString();
    when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

    assertThatThrownBy(() -> storage.read(key)).isInstanceOf(StorageKeyNotFoundException.class);
  }

  @Test
  void aSdkFailureOnReadIsWrappedAsStorageException() {
    String key = UUID.randomUUID().toString();
    when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(SdkException.create("boom", null));

    assertThatThrownBy(() -> storage.read(key)).isInstanceOf(StorageException.class);
  }

  @Test
  void aSdkFailureOnStoreIsWrappedAsStorageException() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenThrow(SdkException.create("boom", null));

    assertThatThrownBy(() -> storage.store("bytes".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(StorageException.class);
  }

  @Test
  void deleteIsANoOpNotAnErrorEvenIfTheKeyWasNeverReachable() {
    String key = UUID.randomUUID().toString();

    storage.delete(key);

    var captor = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(captor.getValue().key()).isEqualTo(key);
  }

  @Test
  void aSdkFailureOnDeleteIsWrappedAsStorageException() {
    String key = UUID.randomUUID().toString();
    when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkException.create("boom", null));

    assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(StorageException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"../etc/passwd", "not-a-uuid", "", "abc/def"})
  void rejectsAMalformedKeyBeforeAnyNetworkCall(String malformedKey) {
    assertThatThrownBy(() -> storage.read(malformedKey)).isInstanceOf(InvalidStorageKeyException.class);
    assertThatThrownBy(() -> storage.delete(malformedKey)).isInstanceOf(InvalidStorageKeyException.class);

    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsANullKeyBeforeAnyNetworkCall() {
    assertThatThrownBy(() -> storage.read(null)).isInstanceOf(InvalidStorageKeyException.class);

    verifyNoInteractions(s3Client);
  }
}
