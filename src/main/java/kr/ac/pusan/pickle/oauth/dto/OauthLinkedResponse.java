package kr.ac.pusan.pickle.oauth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contract schema {@code OauthLinkedResponse} — a Google account was attached
 * to the session that started the flow. No token is issued: the caller was
 * already signed in, and this round trip proved possession of the Google
 * account, not of this one.
 *
 * <p>It exists as a record rather than the bare map it used to be because the
 * callback publishes a {@code oneOf} and a shape absent from that list is a
 * response no generated client can represent.
 */
public record OauthLinkedResponse(
        /**
         * The enum is load-bearing, not documentation. Without it springdoc
         * emits a plain string, and {@code {kind}} carrying no other required
         * field is then a structural subset of every sibling in the union: a
         * registration response would validate against this schema as well,
         * and {@code oneOf} demands exactly one match.
         */
        @Schema(allowableValues = "LINKED") String kind) {

    public static OauthLinkedResponse of() {
        return new OauthLinkedResponse("LINKED");
    }
}
