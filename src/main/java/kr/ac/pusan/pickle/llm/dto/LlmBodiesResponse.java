package kr.ac.pusan.pickle.llm.dto;

/**
 * Ingest tally for one captured-body batch. The gateway discards this body, so
 * it exists for this side's log and its tests — but the four counts are not
 * decoration, and {@code rejected} and {@code skipped} are deliberately not one
 * number.
 *
 * <p>{@code rejected} means the record was malformed: the gateway is shipping
 * something this side cannot store. {@code skipped} means the record was fine
 * and we chose not to keep it — an unknown key, a key whose owner has since
 * turned recording off, or no keyring to encrypt under. Summed into one
 * counter, "the gateway is still capturing for a key that no longer wants it"
 * and "the gateway is sending rubbish" become the same number and neither is
 * visible.</p>
 */
public record LlmBodiesResponse(int accepted, int duplicates, int rejected, int skipped) {
}
