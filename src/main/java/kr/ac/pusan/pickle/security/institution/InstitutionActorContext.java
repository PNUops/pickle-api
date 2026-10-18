package kr.ac.pusan.pickle.security.institution;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** An invalid institution context never becomes an empty or role-derived authority. */
public final class InstitutionActorContext {
    public enum State { VALID_EMPTY, VALID_SOURCES, INVALID }
    public enum Failure { NOT_LOADED, UNCONVERTED, CORRUPT_PAYLOAD, ROLE_MISMATCH, ACCOUNT_ROLE_MISMATCH }

    private final State state;
    private final @Nullable InstitutionAuthority authority;
    private final @Nullable Failure failure;

    private InstitutionActorContext(State state, @Nullable InstitutionAuthority authority, @Nullable Failure failure) {
        this.state = state; this.authority = authority; this.failure = failure;
    }

    public static InstitutionActorContext valid(InstitutionAuthority authority) {
        Objects.requireNonNull(authority);
        return new InstitutionActorContext(authority.assignments().isEmpty() ? State.VALID_EMPTY : State.VALID_SOURCES,
                authority, null);
    }

    public static InstitutionActorContext invalid(Failure failure) {
        return new InstitutionActorContext(State.INVALID, null, Objects.requireNonNull(failure));
    }

    public State state() { return state; }
    public Optional<Failure> failure() { return Optional.ofNullable(failure); }
    public Optional<InstitutionAuthority> authority() { return Optional.ofNullable(authority); }
}
