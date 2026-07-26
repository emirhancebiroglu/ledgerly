package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseStubService {

  private final ExpenseStubRepository expenseStubRepository;

  public ExpenseStubService(ExpenseStubRepository expenseStubRepository) {
    this.expenseStubRepository = expenseStubRepository;
  }

  @Transactional
  public ExpenseStubResponse create(CreateExpenseRequest request, AuthenticatedPrincipal principal) {
    ExpenseStub saved =
        expenseStubRepository.save(
            new ExpenseStub(principal.organizationId(), request.amountMinor(), request.currency()));
    return ExpenseStubResponse.from(saved);
  }
}
