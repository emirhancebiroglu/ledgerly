package com.ledgerly.api.document;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Validates that a PDF with a matching header can actually be parsed before it consumes quota. */
final class PdfDocumentValidator {

  private PdfDocumentValidator() {}

  static void validate(byte[] content) {
    try (PDDocument document = Loader.loadPDF(content)) {
      // Opening and closing the document is the structural validation. The extraction service owns
      // invoice semantics; this boundary only rejects malformed or password-protected PDF bytes.
      if (document.getNumberOfPages() == 0) {
        throw new UnsupportedDocumentTypeException("Uploaded PDF has no pages");
      }
    } catch (IOException exception) {
      throw new UnsupportedDocumentTypeException("Uploaded PDF is malformed or password-protected");
    }
  }
}
