package kr.ac.pusan.pickle.oauth;

/** What an authorization-code round trip is being used for. */
public enum OauthPurpose {

    /** Sign in, or register if the verified address has no account yet. */
    LOGIN,

    /**
     * Sudo-mode proof for an account with no password. The authorization URL
     * carries {@code prompt=login} so Google actually re-authenticates: without
     * it an existing Google session sails through and the "reverification"
     * proves nothing at all.
     */
    REVERIFY,

    /** Attach Google to an account that already exists and is signed in. */
    LINK
}
