package kr.ac.pusan.pickle.llm.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Gateway → api opted-in prompt/response text (internal contract). Held in
 * memory gateway-side and posted directly — never spooled to its disk — so a
 * refused batch is text lost, though never accounting (the usage event went
 * to the durable spool regardless).
 *
 * <p>No bean-validation constraints, for the same reason as
 * {@link LlmUsageRequest}: a non-2xx costs captured text.</p>
 */
public record LlmBodiesRequest(
        String agentVersion,
        List<BodyRecord> records) {

    /**
     * One captured exchange. {@code request} is either the messages array as
     * sent or — when truncated — a JSON <i>string</i> holding the prefix
     * (cutting JSON mid-way produces nothing a parser will take), which is why
     * it is a {@link JsonNode} and not a typed shape. The two truncation flags
     * are separate because a cut prompt and a cut answer mean different things
     * to whoever reads the record.
     */
    public record BodyRecord(
            String eventUuid,
            String keyId,
            String requestedAt,
            JsonNode request,
            String response,
            Boolean requestTruncated,
            Boolean responseTruncated) {
    }
}
