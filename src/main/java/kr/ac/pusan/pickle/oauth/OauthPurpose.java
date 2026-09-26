package kr.ac.pusan.pickle.oauth;

/** What an authorization-code round trip is being used for. */
public enum OauthPurpose {

    /** Sign in, or register if the verified address has no account yet. */
    LOGIN,

    /** Attach Google to an account that already exists and is signed in. */
    LINK
}
