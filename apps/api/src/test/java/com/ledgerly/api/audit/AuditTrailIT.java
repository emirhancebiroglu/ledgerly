package com.ledgerly.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.api.auth.AppUser;
import com.ledgerly.api.auth.AppUserRepository;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.auth.Organization;
import com.ledgerly.api.auth.OrganizationRepository;
import com.ledgerly.api.expense.CreateExpenseRequest;
import com.ledgerly.api.expense.ExpenseStubRepository;
import com.ledgerly.api.expense.ExpenseStubService;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * T5: the audit row is written inside the same transaction as the mutating change it
 * describes, so both commit or both roll back — never one without the other.
 */
class AuditTrailIT extends AbstractPostgresIT {

  @Autowired private ExpenseStubService expenseStubService;
  @Autowired private ExpenseStubRepository expenseStubRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AppUserRepository appUserRepository;

  @Test
  void successfulMutatingCallProducesExactlyOneAuditRowWithActorAndBeforeAfterAndCorrelationId() {
    AppUser user = registerUser();
    AuthenticatedPrincipal principal = new AuthenticatedPrincipal(user.getId(), user.getOrganizationId());

    var response = expenseStubService.create(new CreateExpenseRequest(1500, "EUR"), principal);

    var auditRows = auditLogRepository.findByEntityId(response.id());
    assertThat(auditRows).hasSize(1);

    AuditLog row = auditRows.get(0);
    assertThat(row.getActor()).isEqualTo(user.getId());
    assertThat(row.getAction()).isEqualTo("CREATE");
    assertThat(row.getEntityType()).isEqualTo("expense_stub");
    assertThat(row.getBefore()).isNull();
    assertThat(row.getAfter()).contains("1500").contains("EUR");
    assertThat(row.getCorrelationId()).isNotNull();
  }

  @Test
  void forcedFailurePartwayThroughTheTransactionRollsBackBothTheChangeAndItsAuditRow() {
    AppUser user = registerUser();
    // A principal whose userId does not correspond to any app_user row: the expense insert
    // succeeds, but the audit row's actor FK constraint fails on flush — forcing a failure
    // strictly after the business write within the same transaction.
    AuthenticatedPrincipal principalWithNonexistentActor =
        new AuthenticatedPrincipal(UUID.randomUUID(), user.getOrganizationId());

    long expenseCountBefore = expenseStubRepository.countByOrganizationId(user.getOrganizationId());

    assertThatThrownBy(
            () ->
                expenseStubService.create(
                    new CreateExpenseRequest(2500, "EUR"), principalWithNonexistentActor))
        .isInstanceOf(DataAccessException.class);

    assertThat(expenseStubRepository.countByOrganizationId(user.getOrganizationId()))
        .isEqualTo(expenseCountBefore);
    assertThat(auditLogRepository.findAll())
        .noneMatch(row -> row.getActor().equals(principalWithNonexistentActor.userId()));
  }

  private AppUser registerUser() {
    Organization organization = organizationRepository.save(new Organization("org-" + UUID.randomUUID(), "EUR"));
    return appUserRepository.save(
        new AppUser(organization.getId(), "user-" + UUID.randomUUID() + "@example.com", "hash"));
  }
}
