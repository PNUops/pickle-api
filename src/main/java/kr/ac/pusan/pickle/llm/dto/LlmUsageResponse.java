package kr.ac.pusan.pickle.llm.dto;

/**
 * Ingest tally for one usage batch. The gateway discards this body — it is
 * for the api's own log — but the split matters there: {@code duplicates} are
 * ordinary at-least-once re-sends, while a growing {@code rejected} means the
 * gateway is shipping events this side cannot store.
 */
public record LlmUsageResponse(int accepted, int duplicates, int rejected) {
}
