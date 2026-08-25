package com.ledgerly.api.demo;

import com.ledgerly.api.auth.AppUser;
import com.ledgerly.api.auth.AppUserRepository;
import com.ledgerly.api.auth.AuthService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.budget.BudgetRequest;
import com.ledgerly.api.budget.BudgetService;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.document.DocumentStatus;
import com.ledgerly.api.document.DocumentStatusTransitions;
import com.ledgerly.api.document.DocumentUploadService;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.document.ExtractionProposalValidator;
import com.ledgerly.api.document.ProposalMapper;
import com.ledgerly.api.document.ProposalValidationResult;
import com.ledgerly.api.expense.CategorizeResponse;
import com.ledgerly.api.expense.ExpensePostingTransactions;
import com.ledgerly.api.expense.ExpenseRepository;
import com.ledgerly.api.policy.EmbedPolicyResponse;
import com.ledgerly.api.policy.PolicyDocument;
import com.ledgerly.api.policy.PolicyUploadTransactions;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Populates a demo organization end-to-end (policies, invoices, expenses, a budget, alerts)
 * from T5.1–T5.4's pre-generated PDFs and recorded fixtures — no LLM/embedding call at runtime,
 * since categorization and embedding results are replayed from what was really returned when
 * they were recorded (see docs/decisions.md, 2026-08-25 entries). Idempotent: does nothing on a
 * second run against the same database.
 *
 * <p>The one real network call this makes is anomaly's advisory explanation, which fires from
 * {@link com.ledgerly.api.expense.ExpensePostingTransactions#recordPosted} publishing {@code
 * ExpensePostedEvent} — a deliberate exception (see docs/decisions.md): the anomaly explanation
 * is genuinely generated at post time in production too, so faking it here would make the demo
 * alert say something the real system never said.
 */
@Component
@Profile("demo")
public class DemoSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

  private static final String DEMO_EMAIL = "demo@ledgerly.dev";
  private static final String DEMO_PASSWORD = "ledgerly-demo-account-2026";
  private static final String DEMO_COMPANY = "Ledgerly Demo Co.";
  private static final String DEMO_FULL_NAME = "Demo User";

  private final AppUserRepository appUserRepository;
  private final AuthService authService;
  private final CategoryRepository categoryRepository;
  private final BudgetService budgetService;
  private final PolicyUploadTransactions policyUploadTransactions;
  private final DocumentUploadService documentUploadService;
  private final DocumentStatusTransitions documentStatusTransitions;
  private final ExpensePostingTransactions expensePostingTransactions;
  private final ProposalMapper proposalMapper;
  private final ExtractionProposalValidator extractionProposalValidator;
  private final ExpenseRepository expenseRepository;
  private final DemoSeedFixtures fixtures;
  private final JdbcTemplate jdbcTemplate;

  public DemoSeedRunner(
      AppUserRepository appUserRepository,
      AuthService authService,
      CategoryRepository categoryRepository,
      BudgetService budgetService,
      PolicyUploadTransactions policyUploadTransactions,
      DocumentUploadService documentUploadService,
      DocumentStatusTransitions documentStatusTransitions,
      ExpensePostingTransactions expensePostingTransactions,
      ProposalMapper proposalMapper,
      ExtractionProposalValidator extractionProposalValidator,
      ExpenseRepository expenseRepository,
      DemoSeedFixtures fixtures,
      JdbcTemplate jdbcTemplate) {
    this.appUserRepository = appUserRepository;
    this.authService = authService;
    this.categoryRepository = categoryRepository;
    this.budgetService = budgetService;
    this.policyUploadTransactions = policyUploadTransactions;
    this.documentUploadService = documentUploadService;
    this.documentStatusTransitions = documentStatusTransitions;
    this.expensePostingTransactions = expensePostingTransactions;
    this.proposalMapper = proposalMapper;
    this.extractionProposalValidator = extractionProposalValidator;
    this.expenseRepository = expenseRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.fixtures = fixtures;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    var existingUser = appUserRepository.findByEmail(DEMO_EMAIL);
    if (existingUser.isPresent() && seedIsComplete(existingUser.get())) {
      log.info("Demo seed: {} already exists, skipping", DEMO_EMAIL);
      return;
    }

    // A prior run's user row can exist without the seed being complete (crash mid-run) — reuse
    // it rather than trying to register a second time, which would fail on the unique email.
    var principal =
        existingUser
            .map(user -> new AuthenticatedPrincipal(user.getId(), user.getOrganizationId()))
            .orElseGet(this::registerDemoUser);

    log.info("Demo seed: creating {}", DEMO_EMAIL);
    seedPolicies(principal);
    seedBudget(principal);
    // Last, deliberately: an incomplete prior run (crashed after the user or policies existed
    // but before every invoice posted) must not read as "already exists" on the next boot —
    // completion is judged by the last thing this method does, not the first.
    seedInvoices(principal);
    log.info("Demo seed: done");
  }

  /**
   * Completion is judged by counting posted expenses against the fixture, not by the user row's
   * mere existence — a crash between registering the user and posting the last invoice must not
   * read as "already exists" on the next boot and leave the demo org permanently incomplete.
   */
  private boolean seedIsComplete(AppUser user) {
    int expectedInvoiceCount;
    try {
      expectedInvoiceCount = fixtures.readInvoices().size();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read demo seed invoice fixture", e);
    }
    return expenseRepository.countByOrganizationId(user.getOrganizationId()) >= expectedInvoiceCount;
  }

  private AuthenticatedPrincipal registerDemoUser() {
    var response =
        authService.register(
            new RegisterRequest(DEMO_FULL_NAME, DEMO_COMPANY, DEMO_EMAIL, DEMO_PASSWORD));
    var user = appUserRepository.findByEmail(DEMO_EMAIL).orElseThrow();
    return new AuthenticatedPrincipal(user.getId(), user.getOrganizationId());
  }

  private void seedPolicies(AuthenticatedPrincipal principal) throws IOException {
    for (DemoSeedFixtures.PolicyFixture policy : fixtures.readPolicies()) {
      byte[] content = readPdf("policies", policy.filename());
      PolicyDocument document =
          policyUploadTransactions.createPendingDocument(content, policy.filename(), principal, true);
      policyUploadTransactions.markProcessing(document.getId(), principal.organizationId());

      List<EmbedPolicyResponse.Chunk> chunks =
          policy.chunks().stream()
              .map(c -> new EmbedPolicyResponse.Chunk(c.chunkIndex(), c.chunkText(), c.embedding()))
              .toList();
      var response =
          new EmbedPolicyResponse(
              document.getId().toString(),
              "voyage/voyage-3 (recorded)",
              chunks.isEmpty() ? 0 : chunks.get(0).embedding().size(),
              chunks);
      policyUploadTransactions.recordEmbedded(document.getId(), principal, response);
    }
  }

  private void seedBudget(AuthenticatedPrincipal principal) {
    // Sized so the 4 Boardroom Bistro invoices ($185+$210+$165+$198 = $758) cross both the 80%
    // and 100% BUDGET_THRESHOLD alert boundaries. Period is the current month, not the
    // invoices' own (fixed, past) document_date: BudgetThresholdEvaluator keys off the ledger
    // posting instant (Instant.now() at seed time), not the document date — see
    // docs/decisions.md, 2026-08-25 entry on this.
    Category mealsAndEntertainment =
        categoryRepository
            .findByOrganizationIdAndName(principal.organizationId(), "Meals & Entertainment")
            .orElseThrow();
    String currentPeriod = YearMonth.now(ZoneOffset.UTC).toString();
    budgetService.create(
        new BudgetRequest(mealsAndEntertainment.getId(), currentPeriod, 70000, "USD"), principal);
  }

  private void seedInvoices(AuthenticatedPrincipal principal) throws IOException {
    for (DemoSeedFixtures.InvoiceFixture invoice : fixtures.readInvoices()) {
      String filename = invoice.sourcePdf().substring(invoice.sourcePdf().lastIndexOf('/') + 1);
      byte[] content = readPdf("invoices", filename);

      var document = documentUploadService.upload(content, filename, principal);
      documentStatusTransitions.markProcessing(document.getId(), principal.organizationId());

      ExtractionProposal recordedProposal = proposalMapper.parse(invoice.extraction().toString());
      ExtractionProposal proposal = withDocumentId(recordedProposal, document.getId());
      ProposalValidationResult validation = extractionProposalValidator.validate(proposal);
      var outcomeDocument =
          documentStatusTransitions.recordOutcome(
              document.getId(), principal.organizationId(), proposalMapper.toJson(proposal), validation);

      if (outcomeDocument.getStatus() == DocumentStatus.EXTRACTED && proposal.totalMinor() != 0L) {
        Category category =
            categoryRepository
                .findByOrganizationIdAndName(
                    principal.organizationId(), invoice.categorization().category())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Demo seed fixture references an unknown category: "
                                + invoice.categorization().category()));
        var categorizeResponse =
            new CategorizeResponse(
                document.getId().toString(),
                category.getName(),
                invoice.categorization().confidence(),
                invoice.categorization().citation(),
                "anthropic/qwen3.7-plus (recorded)");
        expensePostingTransactions.recordPosted(
            principal.organizationId(),
            document.getId(),
            principal.userId(),
            category,
            proposal,
            categorizeResponse);
        backdateTimestamps(document.getId(), filename, proposal.documentDate());
      }
    }
  }

  /**
   * {@code recordPosted} stamps every row it writes with {@code Instant.now()} — correct for a
   * real posting, wrong for a demo meant to look like ~3 months of real usage with a dashboard
   * that has something to compare "this month" against. Three groups, by filename:
   *
   * <ul>
   *   <li>Boardroom Bistro (`BUDGET_THRESHOLD`) and TechGear's June invoices stay at posting
   *       time (this month, whatever month the seed actually runs in) — Boardroom because
   *       {@code seedBudget}'s budget period is {@code YearMonth.now()} and backdating it
   *       would silently stop `BUDGET_THRESHOLD` from firing; TechGear deliberately joins it so
   *       the dashboard's "this month" view shows two categories, not one.
   *   <li>One CloudHost and one QuickPrint invoice move to last month (also relative to seed
   *       time, not their own {@code document_date}) — with nothing seeded there otherwise,
   *       the dashboard's month-over-month comparison would have no prior-month baseline to
   *       show a real percentage change against.
   *   <li>Everything else backdates to its own {@code document_date} (2026-03..2026-05),
   *       giving the org a believable multi-month history without disturbing either of the
   *       two groups above.
   * </ul>
   *
   * <p>Plain UPDATEs, not JPA saves: {@code created_at} is {@code updatable = false} on every
   * entity here by design (an upload/posting timestamp is normally immutable audit history) —
   * this bypasses that deliberately, for demo data only, never for a real write path.
   */
  private void backdateTimestamps(UUID documentId, String filename, LocalDate documentDate) {
    boolean staysAtPostingTime =
        filename.startsWith("boardroom_bistro") || filename.startsWith("techgear_2026-06");
    if (staysAtPostingTime) {
      return;
    }

    boolean movesToLastMonth =
        filename.equals("cloudhost_2026-03.pdf") || filename.equals("quickprint_2026-03.pdf");
    LocalDate targetDate =
        movesToLastMonth
            ? YearMonth.now(ZoneOffset.UTC).minusMonths(1).atDay(Math.min(documentDate.getDayOfMonth(), 28))
            : documentDate;

    Instant backdatedAt = targetDate.atTime(10, 0).toInstant(ZoneOffset.UTC);
    jdbcTemplate.update(
        "UPDATE document SET created_at = ?, updated_at = ? WHERE id = ?",
        java.sql.Timestamp.from(backdatedAt),
        java.sql.Timestamp.from(backdatedAt),
        documentId);
    jdbcTemplate.update(
        "UPDATE document_activity SET created_at = ? WHERE document_id = ?",
        java.sql.Timestamp.from(backdatedAt),
        documentId);
    jdbcTemplate.update(
        "UPDATE ledger_transaction SET posted_at = ?, created_at = ? "
            + "WHERE id = (SELECT ledger_transaction_id FROM expense WHERE document_id = ?)",
        java.sql.Timestamp.from(backdatedAt),
        java.sql.Timestamp.from(backdatedAt),
        documentId);
    jdbcTemplate.update(
        "UPDATE ledger_entry SET created_at = ? "
            + "WHERE transaction_id = (SELECT ledger_transaction_id FROM expense WHERE document_id = ?)",
        java.sql.Timestamp.from(backdatedAt),
        documentId);
    jdbcTemplate.update(
        "UPDATE expense SET created_at = ? WHERE document_id = ?",
        java.sql.Timestamp.from(backdatedAt),
        documentId);
  }

  private ExtractionProposal withDocumentId(ExtractionProposal recorded, UUID newDocumentId) {
    return new ExtractionProposal(
        newDocumentId.toString(),
        recorded.vendor(),
        recorded.currency(),
        recorded.totalMinor(),
        recorded.taxMinor(),
        recorded.documentDate(),
        recorded.lines(),
        recorded.confidence(),
        recorded.model(),
        recorded.warnings(),
        recorded.invoiceNumber());
  }

  private byte[] readPdf(String subdir, String filename) throws IOException {
    String path = "/db/seed/pdfs/" + subdir + "/" + filename;
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      if (stream == null) {
        throw new IOException("Missing demo seed PDF: " + path);
      }
      return stream.readAllBytes();
    }
  }
}
