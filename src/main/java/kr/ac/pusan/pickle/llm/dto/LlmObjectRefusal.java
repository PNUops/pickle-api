package kr.ac.pusan.pickle.llm.dto;

/**
 * A refusal the gateway acts on by code. Internal only, so it is not one of
 * the public error codes: {@code KEY_NOT_ACTIVE}, {@code NO_ACCOUNT},
 * {@code INTENT_UNKNOWN}, {@code INTENT_BOUND_ELSEWHERE}, {@code OBJECT_TAKEN},
 * {@code RELEASE_TARGET_INVALID}.
 */
public record LlmObjectRefusal(String code) {
}
