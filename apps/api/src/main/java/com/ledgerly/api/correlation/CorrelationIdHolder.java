package com.ledgerly.api.correlation;

import org.slf4j.MDC;

/** Reads the correlation id the edge filter put into MDC for the current request thread. */
public final class CorrelationIdHolder {

  public static final String MDC_KEY = "correlationId";

  private CorrelationIdHolder() {}

  public static String current() {
    return MDC.get(MDC_KEY);
  }
}
