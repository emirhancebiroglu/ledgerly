package com.ledgerly.api.document;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Validates that a PDF with a matching header can actually be parsed before it consumes quota. */
final class PdfDocumentValidator {

  private static final byte[] EOF_MARKER = "%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private PdfDocumentValidator() {}

  static void validate(byte[] content) {
    if (hasMultipartBoundaryAfterFinalEof(content)) {
      throw new UnsupportedDocumentTypeException("Uploaded PDF contains trailing multipart data");
    }
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

  private static boolean hasMultipartBoundaryAfterFinalEof(byte[] content) {
    int markerStart = -1;
    for (int index = 0; index <= content.length - EOF_MARKER.length; index++) {
      boolean markerMatches = true;
      for (int markerIndex = 0; markerIndex < EOF_MARKER.length; markerIndex++) {
        if (content[index + markerIndex] != EOF_MARKER[markerIndex]) {
          markerMatches = false;
          break;
        }
      }
      if (markerMatches) {
        markerStart = index;
      }
    }
    if (markerStart < 0) {
      return false;
    }
    for (int index = markerStart + EOF_MARKER.length; index < content.length - 2; index++) {
      if ((index == markerStart + EOF_MARKER.length || content[index - 1] == '\n')
          && content[index] == '-'
          && content[index + 1] == '-') {
        return true;
      }
    }
    return false;
  }
}
