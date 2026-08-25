package kr.ac.pusan.pickle.oauth.dto;

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
public record OauthLinkedResponse(String kind) {

    public static OauthLinkedResponse of() {
        return new OauthLinkedResponse("LINKED");
    }
}
