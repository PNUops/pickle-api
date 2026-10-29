package kr.ac.pusan.pickle.llm;

import org.springframework.http.HttpStatus;

/**
 * A ledger refusal the gateway acts on by code. Thrown out of the
 * transaction, so nothing the refused call started is kept.
 */
public class LlmObjectRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public LlmObjectRefusedException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
