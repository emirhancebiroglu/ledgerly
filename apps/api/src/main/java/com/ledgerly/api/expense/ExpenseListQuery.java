package com.ledgerly.api.expense;

/**
 * Validated parameters for {@code GET /api/v1/expenses}. Constructed only via {@link #parse},
 * which is where an unknown sort field becomes a 400 rather than reaching the repository as an
 * arbitrary {@code ORDER BY} column.
 */
public record ExpenseListQuery(
    ExpenseStatus status, String search, ExpenseSortField sortField, boolean descending) {

  public enum ExpenseSortField {
    CREATED_AT,
    AMOUNT_MINOR
  }

  public static ExpenseListQuery parse(String status, String search, String sort) {
    ExpenseStatus parsedStatus = null;
    if (status != null && !status.isBlank()) {
      try {
        parsedStatus = ExpenseStatus.valueOf(status);
      } catch (IllegalArgumentException e) {
        throw new InvalidExpenseListQueryException("Unknown status: " + status);
      }
    }

    String parsedSearch = (search == null || search.isBlank()) ? null : search.trim();

    ExpenseSortField field = ExpenseSortField.CREATED_AT;
    boolean desc = true;
    if (sort != null && !sort.isBlank()) {
      String[] parts = sort.split(",", 2);
      field =
          switch (parts[0]) {
            case "date" -> ExpenseSortField.CREATED_AT;
            case "amount" -> ExpenseSortField.AMOUNT_MINOR;
            default -> throw new InvalidExpenseListQueryException("Unknown sort field: " + parts[0]);
          };
      if (parts.length == 2) {
        if (parts[1].equals("asc")) {
          desc = false;
        } else if (parts[1].equals("desc")) {
          desc = true;
        } else {
          throw new InvalidExpenseListQueryException("Unknown sort direction: " + parts[1]);
        }
      }
    }

    return new ExpenseListQuery(parsedStatus, parsedSearch, field, desc);
  }
}
