package kr.ac.pusan.pickle.llm.dto;

import java.util.List;

/**
 * The subset of the asked ids this key owns. Everything else — another key's
 * object, an unknown id, a released or still pending one, an unknown key —
 * is simply absent, so the answer cannot be used to probe other keys.
 */
public record LlmObjectLookupResponse(List<String> owned) {
}
