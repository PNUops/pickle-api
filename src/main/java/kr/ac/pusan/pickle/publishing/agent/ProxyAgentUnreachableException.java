package kr.ac.pusan.pickle.publishing.agent;

/**
 * A route apply could not reach proxy-agent at all ({@link ApplyOutcome.Kind#TRANSPORT}).
 * Thrown by the route-apply job <b>after</b> its transaction committed, purely so
 * JobRunr retries the enqueued apply with backoff — a 422 (config rejected) never
 * throws, because retrying the same config cannot succeed.
 */
public class ProxyAgentUnreachableException extends RuntimeException {

    public ProxyAgentUnreachableException(String message) {
        super(message);
    }
}
