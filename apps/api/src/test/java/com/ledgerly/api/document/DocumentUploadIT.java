package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/documents} — the upload half of the M4 pipeline: authentication, size cap,
 * magic-byte identification, storage and idempotency.
 *
 * <p>Upload now persists durable {@code PENDING} work. What happens after the queue claims it is
 * {@link DocumentStatusPipelineIT}'s subject; here the accepted-upload status only proves the
 * document was recorded without depending on agent availability.
 */
@AutoConfigureMockMvc
@Import(DocumentUploadIT.AcceptingExtractionConfig.class)
class DocumentUploadIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  private static final byte[] REAL_PDF = TestPdfFactory.validPdf();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocumentRepository documentRepository;

  @Value("${ledgerly.document.max-bytes}")
  private long maxBytes;

  @Test
  void aValidPdfIsAcceptedAndRecorded() throws Exception {
    String token = registerAndGetAccessToken();

    upload(token, "invoice.pdf", REAL_PDF, "key-" + System.nanoTime())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.contentType").value("application/pdf"))
        .andExpect(jsonPath("$.filename").value("invoice.pdf"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void everyResponseFromThisEndpointCarriesNosniff() throws Exception {
    String token = registerAndGetAccessToken();

    upload(token, "invoice.pdf", REAL_PDF, "key-" + System.nanoTime())
        .andExpect(status().isCreated())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("X-Content-Type-Options", "nosniff"));

    // A rejected upload is also a response this application sends — nosniff is a blanket
    // control, not one applied only to the happy path.
    upload(token, "bad.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8), "key-" + System.nanoTime())
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void aNewlyCreatedDocumentStartsPendingBeforeProcessing() {
    Document fresh =
        new Document(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "invoice.pdf",
            "application/pdf",
            REAL_PDF.length,
            UUID.randomUUID().toString(),
            "hash");

    assertThat(fresh.getStatus()).isEqualTo(DocumentStatus.PENDING);
  }

  @Test
  void aPdfNamedFileWhoseBytesAreNotAPdfIsRejectedWith415() throws Exception {
    String token = registerAndGetAccessToken();
    byte[] notAPdf = "<html><body>definitely not a pdf</body></html>".getBytes(StandardCharsets.UTF_8);

    upload(token, "invoice.pdf", notAPdf, "key-" + System.nanoTime())
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void aPdfHeaderWithMalformedPdfBytesIsRejectedWith415BeforeItIsStored() throws Exception {
    String token = registerAndGetAccessToken();
    UUID organizationId = organizationIdOf(token);
    byte[] malformedPdf = "%PDF-1.7\nnot a PDF body\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    upload(token, "malformed.pdf", malformedPdf, "key-" + System.nanoTime())
        .andExpect(status().isUnsupportedMediaType());

    assertThat(documentRepository.countByOrganizationId(organizationId)).isZero();
  }

  @Test
  void aStructurallyValidPdfWithoutPagesIsRejectedWith415BeforeItIsStored() throws Exception {
    String token = registerAndGetAccessToken();
    UUID organizationId = organizationIdOf(token);

    upload(token, "empty.pdf", TestPdfFactory.emptyPdf(), "key-" + System.nanoTime())
        .andExpect(status().isUnsupportedMediaType());

    assertThat(documentRepository.countByOrganizationId(organizationId)).isZero();
  }

  @Test
  void aDeclaredPdfContentTypeOnNonPdfBytesIsStillRejected() throws Exception {
    String token = registerAndGetAccessToken();
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "invoice.pdf",
            MediaType.APPLICATION_PDF_VALUE,
            "MZ\\u0090\\u0000executable".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void aRealJpegAndRealPngAreAccepted() throws Exception {
    String token = registerAndGetAccessToken();
    byte[] jpeg = concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, new byte[64]);
    byte[] png =
        concat(
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, new byte[64]);

    upload(token, "receipt.jpg", jpeg, "key-" + System.nanoTime())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.contentType").value("image/jpeg"));
    upload(token, "receipt.png", png, "key-" + System.nanoTime())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.contentType").value("image/png"));
  }

  @Test
  void anEmptyUploadIsRejected() throws Exception {
    String token = registerAndGetAccessToken();

    upload(token, "empty.pdf", new byte[0], "key-" + System.nanoTime())
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void overTheSizeCapReturns413() throws Exception {
    String token = registerAndGetAccessToken();
    byte[] oversized = new byte[(int) maxBytes + 1];
    System.arraycopy(REAL_PDF, 0, oversized, 0, REAL_PDF.length);

    upload(token, "huge.pdf", oversized, "key-" + System.nanoTime())
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void unauthenticatedReturns401() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "invoice.pdf", null, REAL_PDF))
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aReplayedIdempotencyKeyReturnsTheFirstResponseWithoutStoringASecondBlob() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    String key = "key-" + System.nanoTime();

    MvcResult first =
        upload(token, "invoice.pdf", REAL_PDF, key).andExpect(status().isCreated()).andReturn();
    MvcResult second =
        upload(token, "invoice.pdf", REAL_PDF, key).andExpect(status().isCreated()).andReturn();

    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(documentRepository.countByOrganizationId(orgId)).isEqualTo(1);
  }

  @Test
  void replayingAKeyWithADifferentDocumentIsRejectedRatherThanReturningTheFirst() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    String key = "key-" + System.nanoTime();
    byte[] otherPdf = concat(REAL_PDF, "\n".getBytes(StandardCharsets.UTF_8));

    upload(token, "first.pdf", REAL_PDF, key).andExpect(status().isCreated());

    // A different document under the same key is a client bug, not a retry. Returning the first
    // document's response would silently discard this upload and hand back someone else's data.
    upload(token, "second.pdf", otherPdf, key).andExpect(status().isConflict());
    assertThat(documentRepository.countByOrganizationId(orgId)).isEqualTo(1);
  }

  @Test
  void theStoredKeyNeverAppearsInTheResponse() throws Exception {
    String token = registerAndGetAccessToken();

    MvcResult result =
        upload(token, "invoice.pdf", REAL_PDF, "key-" + System.nanoTime())
            .andExpect(status().isCreated())
            .andReturn();

    String body = result.getResponse().getContentAsString();
    UUID documentId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
    Document stored = documentRepository.findById(documentId).orElseThrow();

    assertThat(body).doesNotContain(stored.getStorageKey());
    assertThat(body).doesNotContain("storageKey", "contentHash");
    // No field in the response is a filesystem path. `createdAt` legitimately contains ':' and
    // '-', so the check is per-field rather than a blanket search of the whole body.
    objectMapper
        .readTree(body)
        .properties()
        .forEach(
            field -> {
              if (field.getValue().isTextual() && !"createdAt".equals(field.getKey())) {
                assertThat(field.getValue().asText())
                    .as("field %s must not look like a path", field.getKey())
                    .doesNotContain("\\")
                    .doesNotContain("..");
              }
            });
  }

  @Test
  void aFilenameCarryingAPathIsStoredAsANameOnly() throws Exception {
    String token = registerAndGetAccessToken();

    MvcResult result =
        upload(token, "../../etc/passwd.pdf", REAL_PDF, "key-" + System.nanoTime())
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("filename").asText())
        .isEqualTo("passwd.pdf");
  }

  @Test
  void theContentHashIsRecordedForTheStoredBytes() throws Exception {
    String token = registerAndGetAccessToken();

    MvcResult result =
        upload(token, "invoice.pdf", REAL_PDF, "key-" + System.nanoTime())
            .andExpect(status().isCreated())
            .andReturn();

    UUID documentId =
        UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    Document stored = documentRepository.findById(documentId).orElseThrow();

    assertThat(stored.getContentHash()).hasSize(64);
    assertThat(stored.getStorageKey()).isNotBlank();
    assertThat(stored.getSizeBytes()).isEqualTo(REAL_PDF.length);
  }

  @Test
  void anotherOrganizationsDocumentIsNotReadable() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();

    MvcResult uploaded =
        upload(tokenA, "invoice.pdf", REAL_PDF, "key-" + System.nanoTime())
            .andExpect(status().isCreated())
            .andReturn();
    String documentId =
        objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/documents/" + documentId)
                .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/documents/" + documentId)
                .header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  @Test
  void aMissingIdempotencyKeyIsRejected() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "invoice.pdf", null, REAL_PDF))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  private ResultActions upload(String token, String filename, byte[] content, String key)
      throws Exception {
    return mockMvc.perform(
        multipart("/api/v1/documents")
            .file(new MockMultipartFile("file", filename, null, content))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key));
  }

  private byte[] concat(byte[] first, byte[] second) {
    byte[] combined = new byte[first.length + second.length];
    System.arraycopy(first, 0, combined, 0, first.length);
    System.arraycopy(second, 0, combined, first.length, second.length);
    return combined;
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "document-user-" + System.nanoTime() + "@example.com";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(), email, "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private UUID organizationIdOf(String accessToken) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  /**
   * Stands in for `ai` with a consistently valid answer, so these tests exercise the upload path
   * rather than the extraction outcomes covered elsewhere.
   */
  @TestConfiguration
  static class AcceptingExtractionConfig {

    @Bean
    @Primary
    ExtractionClient acceptingExtractionClient() {
      return (documentId, content, contentType, filename) ->
          """
          {"document_id":"%s","vendor":"Contoso","currency":"EUR","total_minor":12100,
           "tax_minor":2100,"document_date":"%s",
           "lines":[{"description":"item a","quantity":1000,"amount_minor":4000},
                    {"description":"item b","quantity":1000,"amount_minor":6000}],
           "confidence":{"vendor":0.9,"currency":0.99,"total_minor":0.95,"tax_minor":0.9,
                         "document_date":0.93},
           "model":"fake-llm-v1","warnings":[]}
          """
              .formatted(documentId, LocalDate.now().minusDays(3));
    }
  }
}
