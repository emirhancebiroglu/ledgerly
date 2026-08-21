package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerly.api.category.Category;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.document.DocumentActivityService;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.policy.PolicyChunkRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class ExpensePostingServiceTest {

  @Test
  void deletedCategoryBetweenSelectionAndPostingBecomesACorrectableOutcome() {
    UUID organizationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    Category category = new Category(organizationId, "Travel");
    ReflectionTestUtils.setField(category, "id", categoryId);

    CategoryRepository categories = mock(CategoryRepository.class);
    PolicyChunkRepository policyChunks = mock(PolicyChunkRepository.class);
    CategorizationClient categorizationClient = mock(CategorizationClient.class);
    ExpensePostingTransactions transactions = mock(ExpensePostingTransactions.class);
    DocumentActivityService activityService = mock(DocumentActivityService.class);
    when(categories.findByOrganizationIdOrderByNameAsc(organizationId)).thenReturn(List.of(category));
    when(categories.findByOrganizationIdAndName(organizationId, "Travel"))
        .thenReturn(Optional.of(category));
    when(categories.findByIdAndOrganizationId(categoryId, organizationId)).thenReturn(Optional.empty());
    when(policyChunks.countByOrganizationId(organizationId)).thenReturn(0L);
    when(categorizationClient.categorize(
            any(), any(), any(), any(Long.class), any(), any(), any()))
        .thenReturn(categorizeResponse(documentId, "Travel"));
    when(transactions.recordPosted(any(), any(), any(), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("category foreign key no longer exists"));

    ExpensePostingService service =
        new ExpensePostingService(
            categories,
            policyChunks,
            mock(QueryEmbeddingClient.class),
            categorizationClient,
            new AiResponseMapper(),
            transactions,
            activityService,
            0.7);

    assertThatThrownBy(
            () -> service.categorizeAndPost(organizationId, documentId, UUID.randomUUID(), proposal(documentId)))
        .isInstanceOf(CategorizationOutcomeException.class)
        .hasMessage("Categorization category was deleted before posting");
    verify(transactions).recordPosted(any(), any(), any(), any(), any(), any());
  }

  private static ExtractionProposal proposal(UUID documentId) {
    return new ExtractionProposal(
        documentId.toString(),
        "Contoso",
        "EUR",
        12_100,
        2_100,
        LocalDate.of(2026, 1, 1),
        List.of(
            new ExtractionProposal.Line("item a", 1_000L, 4_000),
            new ExtractionProposal.Line("item b", 1_000L, 6_000)),
        Map.of("vendor", 0.9),
        "fake-llm-v1",
        List.of(),
        null);
  }

  private static String categorizeResponse(UUID documentId, String category) {
    return """
        {"document_id":"%s","category":"%s","confidence":0.92,"citation":null,"model":"fake-llm-v1"}
        """
        .formatted(documentId, category);
  }
}
