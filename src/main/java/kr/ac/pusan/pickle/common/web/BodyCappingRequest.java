package kr.ac.pusan.pickle.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Hard byte cap on a request body. A filter's Content-Length pre-check catches
 * declared sizes; this wrapper is what bounds a chunked (undeclared-length)
 * body — exceeding the cap aborts the read with
 * {@link RequestBodyCapExceededException}, which the global handler maps to
 * the same 413 as a declared-length violation. {@code getReader()} delegates
 * through the same capped stream.
 *
 * <p>Shared by every internal surface that takes a machine-posted body behind
 * its own filter chain (relay sync, the LLM gateway link); each caller picks
 * its own cap.</p>
 */
public final class BodyCappingRequest extends HttpServletRequestWrapper {

    private final long cap;

    public BodyCappingRequest(HttpServletRequest request, long cap) {
        super(request);
        this.cap = cap;
    }

    @Override
    public java.io.BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                encoding != null ? encoding : StandardCharsets.UTF_8.name()));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        ServletInputStream delegate = super.getInputStream();
        return new ServletInputStream() {
            private long read;

            private void count(long n) throws IOException {
                if (n > 0) {
                    read += n;
                    if (read > cap) {
                        throw new RequestBodyCapExceededException(cap);
                    }
                }
            }

            @Override
            public int read() throws IOException {
                int b = delegate.read();
                count(b >= 0 ? 1 : 0);
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = delegate.read(b, off, len);
                count(n);
                return n;
            }

            @Override
            public boolean isFinished() {
                return delegate.isFinished();
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                delegate.setReadListener(readListener);
            }
        };
    }
}
