package com.smit.flightops.security;

import com.smit.flightops.exception.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses a request body over {@code app.http.max-body-bytes} with 413
 * {@code PAYLOAD_TOO_LARGE} before anything parses it. Jackson builds a whole string
 * value before Bean Validation checks its {@code @Size}, so without a cap one field of
 * millions of characters holds tens of MB of heap, and a few such requests at once
 * exhaust it.
 *
 * <p>A declared {@code Content-Length} over the limit is answered here, and the body is
 * never read. The container never reads past a declared length, so a body that declares
 * one needs nothing more. A body without one, which is how a chunked request arrives, is
 * counted as it is read, and the read that passes the limit throws
 * {@link PayloadTooLargeException}. Jackson reports that wrapped in
 * {@code HttpMessageNotReadableException}, and {@code GlobalExceptionHandler} answers
 * with the same 413. Only {@code getInputStream} is counted: it is how Spring MVC reads
 * a JSON body and how {@code FormContentFilter} reads a form one.
 *
 * <p>The order puts it after {@code RequestIdFilter}, so the 413 carries
 * {@code X-Request-Id}. It also puts it before {@code FormContentFilter}, which reads a
 * form-encoded {@code PUT}, {@code PATCH} or {@code DELETE} body in full before Spring
 * Security runs, so that read is counted as well. An oversized declared length or form
 * body without credentials therefore gets 413, not 401. {@code HttpConfig} builds it.
 *
 * @see "doc/api.md, section Request rules"
 */
@Order(OrderedFormContentFilter.DEFAULT_ORDER - 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;
    private final ErrorResponseWriter writer;

    public RequestBodyLimitFilter(long maxBodyBytes, ErrorResponseWriter writer) {
        this.maxBodyBytes = maxBodyBytes;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            reject(response);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(new CountedRequest(request, maxBodyBytes), response);
        } catch (PayloadTooLargeException e) {
            // A body read outside Spring MVC, such as FormContentFilter's. Without
            // this the container would forward the IOException to /error as a 500.
            reject(response);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        writer.write(response, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                PayloadTooLargeException.messageFor(maxBodyBytes));
    }

    /** The request with its input stream counted. One stream per request, as the servlet API returns. */
    private static final class CountedRequest extends HttpServletRequestWrapper {

        private final long maxBodyBytes;
        private ServletInputStream counted;

        CountedRequest(HttpServletRequest request, long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (counted == null) {
                counted = new CountingInputStream(super.getInputStream(), maxBodyBytes);
            }
            return counted;
        }
    }

    /**
     * Throws once more than {@code maxBodyBytes} have been read, and on every read after
     * that. No read asks for more than one byte past the limit, the byte that shows the
     * body is too large. {@code skip} and the bulk reads of {@link java.io.InputStream}
     * all go through the two {@code read} methods.
     */
    private static final class CountingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBodyBytes;
        private long count;

        CountingInputStream(ServletInputStream delegate, long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            refuseIfOver();
            int b = delegate.read();
            if (b != -1) {
                count++;
                refuseIfOver();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            refuseIfOver();
            // Never negative after the check, and compared before adding one, so a
            // limit near Long.MAX_VALUE cannot overflow into a negative length.
            long room = maxBodyBytes - count;
            int n = delegate.read(buffer, offset, room < length ? (int) room + 1 : length);
            if (n > 0) {
                count += n;
                refuseIfOver();
            }
            return n;
        }

        private void refuseIfOver() throws PayloadTooLargeException {
            if (count > maxBodyBytes) {
                throw new PayloadTooLargeException(maxBodyBytes);
            }
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
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
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
