package kr.ac.pusan.pickle.common.error;

/** One entry of the Problem {@code errors[]} extension (contract). */
public record FieldValidationError(String field, String message) {
}
