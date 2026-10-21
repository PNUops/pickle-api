package kr.ac.pusan.pickle.llm.dto;

import java.util.UUID;

/** The intent the gateway binds once the vendor accepts the submit. */
public record LlmObjectIntentResponse(UUID intentId) {
}
