package kr.ac.pusan.pickle.networkpolicy;

/** Desired policy exists but this process cannot safely send it to an agent. */
public class SourcePolicyUnavailableException extends RuntimeException {

    public SourcePolicyUnavailableException(String message) {
        super(message);
    }
}
