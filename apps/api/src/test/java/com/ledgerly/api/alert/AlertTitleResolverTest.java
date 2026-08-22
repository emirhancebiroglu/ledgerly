package com.ledgerly.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ledgerly.api.budget.Budget;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.category.CategoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link AlertTitleResolver}: titles never embed a formatted amount — money display stays a
 * browser-only concern (see {@code formatMoney} in the web client). */
@ExtendWith(MockitoExtension.class)
class AlertTitleResolverTest {

  @Mock private CategoryRepository categoryRepository;

  private AlertTitleResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new AlertTitleResolver(categoryRepository);
  }

  @Test
  void budgetThresholdAtOneHundredPercentNamesTheCategoryAndTheCrossing() {
    UUID orgId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    Category category = new Category(orgId, "Marketing");
    when(categoryRepository.findByIdAndOrganizationId(categoryId, orgId))
        .thenReturn(Optional.of(category));
    Budget budget = new Budget(orgId, categoryId, "2026-08", 600_000L, "EUR");
    Alert alert = Alert.budgetThreshold(orgId, UUID.randomUUID(), budget, 100, 618_000L);

    String title = resolver.resolve(alert);

    assertThat(title).isEqualTo("Marketing budget exceeded");
    assertThat(title).doesNotContainPattern("\\d");
  }

  @Test
  void budgetThresholdAtEightyPercentNamesTheCategoryAsNearingItsBudget() {
    UUID orgId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    Category category = new Category(orgId, "Travel");
    when(categoryRepository.findByIdAndOrganizationId(categoryId, orgId))
        .thenReturn(Optional.of(category));
    Budget budget = new Budget(orgId, categoryId, "2026-08", 20_000_00L, "EUR");
    Alert alert = Alert.budgetThreshold(orgId, UUID.randomUUID(), budget, 80, 18_200_00L);

    String title = resolver.resolve(alert);

    assertThat(title).isEqualTo("Travel nearing its budget");
  }

  @Test
  void anomalyHighTitleIsAFixedPhraseNotTheExplanation() {
    UUID orgId = UUID.randomUUID();
    Alert alert =
        Alert.anomalyHigh(
            orgId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "2026-08",
            "EUR",
            84_500L,
            null,
            12,
            3.4,
            null,
            "Delta Airlines charged 845.00, well above this category's typical spend.",
            "gpt-test");

    String title = resolver.resolve(alert);

    assertThat(title).isEqualTo("Unusual spending detected");
    assertThat(title).isNotEqualTo(alert.getExplanation());
  }

  @Test
  void categoryNotFoundStillProducesAReadableTitle() {
    UUID orgId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    when(categoryRepository.findByIdAndOrganizationId(categoryId, orgId)).thenReturn(Optional.empty());
    Budget budget = new Budget(orgId, categoryId, "2026-08", 100_00L, "EUR");
    Alert alert = Alert.budgetThreshold(orgId, UUID.randomUUID(), budget, 100, 100_00L);

    String title = resolver.resolve(alert);

    assertThat(title).isEqualTo("Uncategorized budget exceeded");
  }
}
