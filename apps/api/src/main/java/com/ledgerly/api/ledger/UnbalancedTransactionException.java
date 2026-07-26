package com.ledgerly.api.ledger;

public class UnbalancedTransactionException extends RuntimeException {

  public UnbalancedTransactionException(String message) {
    super(message);
  }
}
