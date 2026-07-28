package com.ledgerly.api.budget;

public class DuplicateBudgetException extends RuntimeException {

  public DuplicateBudgetException() {
    super("A budget already exists for this category, period and currency");
  }
}
