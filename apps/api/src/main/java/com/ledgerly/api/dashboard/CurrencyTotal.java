package com.ledgerly.api.dashboard;

/**
 * Total spend in one currency. Plural rather than a single number: an organization is not
 * required to post every expense in its {@code base_currency} (only the currency code is
 * validated on extraction, not that it matches the org's base — see {@code
 * ExtractionProposalValidator}), so summing across currencies would silently produce a number in
 * no real unit. The dashboard's headline figure is the entry matching the org's base currency;
 * any other entries are a signal to surface, not average away.
 */
public record CurrencyTotal(String currency, long amountMinor) {}
