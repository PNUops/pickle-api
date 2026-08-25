package kr.ac.pusan.pickle.identity;

/**
 * External login provider (contract schema {@code IdentityProvider}).
 *
 * <p>Google only, and deliberately so: the product spec forbids building a
 * multi-provider abstraction ahead of a second provider actually existing.
 * The enum is here so the column and the contract have one name for the value,
 * not as a seam for providers nobody has asked for.
 */
public enum IdentityProvider {
    GOOGLE
}
