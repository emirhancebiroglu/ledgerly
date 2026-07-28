package com.ledgerly.api.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

/** Adds the static service identity before every Logback event, including startup logs. */
public class ApiServiceMdcTurboFilter extends TurboFilter {

  @Override
  public FilterReply decide(
      Marker marker,
      Logger logger,
      Level level,
      String format,
      Object[] params,
      Throwable throwable) {
    if (MDC.get("service") == null) {
      MDC.put("service", "api");
    }
    return FilterReply.NEUTRAL;
  }
}
