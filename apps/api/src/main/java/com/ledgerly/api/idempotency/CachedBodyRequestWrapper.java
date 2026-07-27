package com.ledgerly.api.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Caches the request body once so it can be hashed for idempotency and still be read again,
 * in full, by the actual handler downstream. {@link org.springframework.web.util.
 * ContentCachingRequestWrapper} does not support this: reading its stream to completion
 * exhausts the underlying stream, leaving nothing for a second reader.
 */
final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

  private final byte[] body;

  CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
    super(request);
    this.body = request.getInputStream().readAllBytes();
  }

  byte[] getBody() {
    return body;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream byteStream = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return byteStream.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        // non-async: body is already fully buffered, nothing to notify
      }

      @Override
      public int read() {
        return byteStream.read();
      }
    };
  }

  @Override
  public java.io.BufferedReader getReader() throws IOException {
    String charset = getCharacterEncoding();
    java.io.InputStreamReader reader =
        charset != null
            ? new java.io.InputStreamReader(getInputStream(), charset)
            : new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
    return new java.io.BufferedReader(reader);
  }
}
