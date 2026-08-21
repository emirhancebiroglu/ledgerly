package com.ledgerly.api.expense;

/** Approval cannot post an unclassified review item; the reviewer must choose an org category. */
public class ExpenseCategoryRequiredException extends RuntimeException {

  public ExpenseCategoryRequiredException() {
    super("Choose a category before approving this expense");
  }
}
