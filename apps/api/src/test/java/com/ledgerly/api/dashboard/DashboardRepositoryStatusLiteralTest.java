package com.ledgerly.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.document.DocumentStatus;
import com.ledgerly.api.expense.ExpenseStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A dashboard SQL string built from a hand-written status literal instead of {@code
 * DocumentStatus}/{@code ExpenseStatus} is what let {@code documentsProcessedSince} keep
 * filtering on {@code 'NEEDS_REVIEW'} after V21 renamed the document status to {@code
 * EXTRACTION_NEEDS_REVIEW} and dropped the old value from the schema's CHECK constraint -- the
 * query kept compiling and kept returning a number, just the wrong one.
 *
 * <p>Checking a literal against the union of both enums would have missed exactly that
 * regression: {@code NEEDS_REVIEW} is a live {@code ExpenseStatus} value, so a document-table
 * query still using it would pass a union check. This test instead splits the source into one
 * block per method (each dashboard method queries exactly one status-bearing table -- {@code
 * expense} or {@code document}), determines that method's table from its {@code FROM}/{@code
 * JOIN} clause, and validates every quoted status literal in that block against only that
 * table's enum.
 */
class DashboardRepositoryStatusLiteralTest {

  // Matches a single-quoted SQL string literal made only of uppercase letters and underscores --
  // the shape every status value in this schema takes ('PENDING', 'EXTRACTED', 'POSTED', ...).
  private static final Pattern STATUS_LITERAL = Pattern.compile("'([A-Z][A-Z_]*)'");

  // Splits the class body into one block per method, starting at each `public`/`private` method
  // declaration up to (not including) the next one -- good enough for this file's flat method
  // list with no nested classes.
  private static final Pattern METHOD_START =
      Pattern.compile("(?m)^\\s*(?:public|private)\\s+.*\\{\\s*$");

  private static final List<String> KNOWN_NON_STATUS_LITERALS =
      List.of("UTC"); // timezone literal used in date_trunc(..., 'UTC') / AT TIME ZONE 'UTC'

  private static final Set<String> DOCUMENT_STATUSES =
      Arrays.stream(DocumentStatus.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

  private static final Set<String> EXPENSE_STATUSES =
      Arrays.stream(ExpenseStatus.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

  @Test
  void everyQuotedStatusLiteralInDashboardRepositoryNamesALiveValueOfItsOwnTablesStatusEnum()
      throws IOException {
    Path source = Path.of("src/main/java/com/ledgerly/api/dashboard/DashboardRepository.java");
    String content = Files.readString(source);

    List<String> violations = new ArrayList<>();
    for (String block : splitIntoMethodBlocks(content)) {
      Set<String> allowedForBlock = allowedStatusesFor(block);
      if (allowedForBlock == null) {
        // The block queries neither expense nor document (e.g. a private timestamp helper) --
        // any status-shaped literal in it is unexpected and worth reporting on its own.
        allowedForBlock = Set.of();
      }
      Matcher matcher = STATUS_LITERAL.matcher(block);
      while (matcher.find()) {
        String literal = matcher.group(1);
        if (KNOWN_NON_STATUS_LITERALS.contains(literal) || allowedForBlock.contains(literal)) {
          continue;
        }
        violations.add(literal + " in: " + firstLineOf(block));
      }
    }

    assertThat(violations)
        .as(
            "DashboardRepository.java contains a quoted literal that does not name a live "
                + "status value of the table its own method queries -- likely a stale status "
                + "string left behind by a rename, or a literal borrowed from the wrong table's "
                + "enum")
        .isEmpty();
  }

  /** Table-scoped: null if the block references neither table's status column at all. */
  private static Set<String> allowedStatusesFor(String block) {
    boolean queriesDocument = block.contains("FROM document");
    boolean queriesExpense = block.contains("FROM expense") || block.contains("JOIN expense");
    if (queriesDocument && queriesExpense) {
      Set<String> union = new java.util.HashSet<>(DOCUMENT_STATUSES);
      union.addAll(EXPENSE_STATUSES);
      return union;
    }
    if (queriesDocument) {
      return DOCUMENT_STATUSES;
    }
    if (queriesExpense) {
      return EXPENSE_STATUSES;
    }
    return null;
  }

  private static List<String> splitIntoMethodBlocks(String content) {
    Matcher matcher = METHOD_START.matcher(content);
    List<Integer> starts = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    List<String> blocks = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1) : content.length();
      blocks.add(content.substring(starts.get(i), end));
    }
    return blocks;
  }

  private static String firstLineOf(String block) {
    int newline = block.indexOf('\n');
    return newline == -1 ? block : block.substring(0, newline).trim();
  }
}
