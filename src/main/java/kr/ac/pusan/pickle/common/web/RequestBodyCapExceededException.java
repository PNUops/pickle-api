package kr.ac.pusan.pickle.common.web;

import java.io.IOException;

/**
 * A request body exceeded a server-side byte cap while being STREAMED (no
 * usable Content-Length — chunked transfer). Thrown from a capping
 * {@code ServletInputStream} wrapper mid-read, it surfaces wrapped in the
 * message-conversion failure; {@code GlobalExceptionHandler} unwraps it so
 * the chunked path answers the same 413 as a declared-length violation.
 */
public class RequestBodyCapExceededException extends IOException {

    public RequestBodyCapExceededException(long capBytes) {
        super("request body exceeded the configured cap of " + capBytes + " bytes");
    }
}
