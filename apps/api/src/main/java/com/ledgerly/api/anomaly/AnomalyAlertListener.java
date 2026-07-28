package com.ledgerly.api.anomaly;

import com.ledgerly.api.expense.ExpenseRepository;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps optional AI latency and failures outside the committed ledger-posting transaction. */
@Component
public class AnomalyAlertListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(AnomalyAlertListener.class);
  private final ExpenseRepository expenseRepository;
  private final ExpenseAnomalyAdvisor advisor;
  private final AnomalyAlertWriter writer;
  public AnomalyAlertListener(ExpenseRepository expenseRepository, ExpenseAnomalyAdvisor advisor, AnomalyAlertWriter writer) {
    this.expenseRepository = expenseRepository; this.advisor = advisor; this.writer = writer;
  }
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPosted(ExpensePostedEvent event) {
    try {
      expenseRepository.findByIdAndOrganizationId(event.expenseId(), event.organizationId()).ifPresent(expense ->
          advisor.assess(expense, event.postedAt()).filter(response -> response.risk() == AnomalyRisk.HIGH)
              .ifPresent(response -> writer.record(expense, response, event.postedAt(), event.actor())));
    } catch (RuntimeException exception) {
      LOGGER.warn("Anomaly alert persistence failed after expense {} committed", event.expenseId(), exception);
    }
  }
}
