package com.ledgerly.api.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What `ai` proposes for a document. Mirrors {@code docs/contracts/extraction-proposal.schema.json}.
 *
 * <p>Every monetary field is a {@code long} of minor units. Binding to {@code long} rather than
 * {@code BigDecimal} means a fractional amount fails at parse time, before any rule runs — the
 * contract says money is an integer, and this is where that is enforced on the `api` side.
 *
 * <p>Nothing in this record is trusted. It is the *claim*; {@link ExtractionProposalValidator}
 * decides whether the claim is admissible.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ExtractionProposal(
    @JsonProperty("document_id") String documentId,
    String vendor,
    String currency,
    @JsonProperty("total_minor") long totalMinor,
    @JsonProperty("tax_minor") long taxMinor,
    @JsonProperty("document_date") LocalDate documentDate,
    List<Line> lines,
    Map<String, Double> confidence,
    String model,
    List<String> warnings,
    @JsonProperty("invoice_number") String invoiceNumber) {

  /** Sum of the line amounts, in minor units. */
  public long lineTotalMinor() {
    if (lines == null) {
      return 0L;
    }
    return lines.stream().mapToLong(Line::amountMinor).sum();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Line(
      String description, Long quantity, @JsonProperty("amount_minor") long amountMinor) {}
}
