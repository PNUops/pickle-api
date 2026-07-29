package kr.ac.pusan.pickle.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import kr.ac.pusan.pickle.common.error.GlobalExceptionHandler;
import kr.ac.pusan.pickle.common.web.RequestBodyCapExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The chunked-body cap path, unit level (MockMvc always declares a
 * Content-Length, so the streaming cap cannot be exercised end-to-end):
 * the wrapper aborts the read past the cap with the dedicated exception, the
 * reader route flows through the same capped stream, and the global handler
 * maps it to 413 — identical to a declared-length violation.
 */
class RelayBodyCapTest {

    @Test
    void cappedStreamAbortsPastTheCap() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/internal/relays/1/sync");
        raw.setContent(new byte[100]);
        RelayAuthFilter.BodyCappingRequest capped =
                new RelayAuthFilter.BodyCappingRequest(raw, 32);
        try (InputStream in = capped.getInputStream()) {
            assertThatThrownBy(() -> in.readAllBytes())
                    .isInstanceOf(RequestBodyCapExceededException.class);
        }
    }

    @Test
    void underCapBodyReadsCompletely() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/internal/relays/1/sync");
        raw.setContent("{\"appliedGeneration\":0}".getBytes());
        RelayAuthFilter.BodyCappingRequest capped =
                new RelayAuthFilter.BodyCappingRequest(raw, 1024);
        try (InputStream in = capped.getInputStream()) {
            assertThat(in.readAllBytes()).hasSize(23);
        }
    }

    @Test
    void readerRouteIsCappedToo() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/internal/relays/1/sync");
        raw.setContent(new byte[100]);
        RelayAuthFilter.BodyCappingRequest capped =
                new RelayAuthFilter.BodyCappingRequest(raw, 32);
        assertThatThrownBy(() -> {
            var reader = capped.getReader();
            while (reader.read() >= 0) {
                // drain until the cap trips
            }
        }).isInstanceOf(RequestBodyCapExceededException.class);
    }

    @Test
    void globalHandlerMapsTheCapExceptionTo413() {
        HttpMessageNotReadableException wrapped = new HttpMessageNotReadableException(
                "read aborted", new RequestBodyCapExceededException(1024),
                new MockHttpInputMessage(new byte[0]));
        var response = new GlobalExceptionHandler().handleUnreadable(wrapped,
                new MockHttpServletRequest("POST", "/internal/relays/1/sync"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }
}
