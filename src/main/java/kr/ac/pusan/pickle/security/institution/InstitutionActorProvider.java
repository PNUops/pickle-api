package kr.ac.pusan.pickle.security.institution;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fresh identity and strict institution sources share one short database snapshot. */
@Component
public class InstitutionActorProvider {
    private final JdbcTemplate jdbc;
    private final InstitutionAssignmentStore store;
    private final TransactionTemplate snapshots;

    public InstitutionActorProvider(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        store = new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec());
        snapshots = new TransactionTemplate(transactionManager);
        snapshots.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots.setReadOnly(true);
    }

    public Optional<AuthenticatedUser> authenticate(UUID publicId, int tokenVersion) {
        if (tokenVersion < 0) { return Optional.empty(); }
        return Objects.requireNonNull(snapshots.execute(status -> {
            requireSnapshotTransaction();
            return load(publicId, tokenVersion);
        }));
    }

    public AuthenticatedUser reloadCurrent(AuthenticatedUser original) {
        return authenticate(original.publicId(), originalVersion(original))
                .filter(current -> current.id().equals(original.id())).orElseThrow(InstitutionActorProvider::unauthorized);
    }

    /** The caller must already hold the administration barrier on this exact transaction connection. */
    public AuthenticatedUser reloadAfterAdministrationLock(AuthenticatedUser original) {
        int tokenVersion = originalVersion(original);
        requireLockedCallerTransaction();
        return load(original.publicId(), tokenVersion).filter(current -> current.id().equals(original.id()))
                .orElseThrow(InstitutionActorProvider::unauthorized);
    }

    private Optional<AuthenticatedUser> load(UUID publicId, int tokenVersion) {
        List<InstitutionActorIdentityRow> identities = jdbc.query("""
                select id,public_id,email,role::text,status::text,token_version from users where public_id=?
                """, (rs, index) -> new InstitutionActorIdentityRow(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                        rs.getString("email"), UserRole.valueOf(rs.getString("role")), rs.getString("status"), rs.getInt("token_version")), publicId);
        if (identities.isEmpty()) { return Optional.empty(); }
        InstitutionActorIdentityRow identity = identities.getFirst();
        if (!"ACTIVE".equals(identity.status()) || identity.tokenVersion() != tokenVersion) { return Optional.empty(); }
        InstitutionActorContext context = context(identity);
        Map<Long, UserRole> fullRows = Map.of();
        if (context.state() == InstitutionActorContext.State.VALID_SOURCES) {
            Map<UUID, InstitutionAssignment> assignments = context.authority().orElseThrow().assignments().stream()
                    .collect(Collectors.toMap(InstitutionAssignment::institutionId, assignment -> assignment));
            fullRows = jdbc.query("""
                    select r.org_id,o.public_id from user_org_roles r join orgs o on o.id=r.org_id where r.user_id=?
                    """, (rs, index) -> Map.entry(rs.getLong("org_id"), rs.getObject("public_id", UUID.class)), identity.id()).stream()
                    .filter(row -> assignments.get(row.getValue()).mode() == InstitutionAssignment.Mode.FULL)
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, row -> UserRole.ORG_ADMIN));
        }
        return Optional.of(new AuthenticatedUser(identity.id(), identity.publicId(), identity.email(), identity.role(),
                fullRows, tokenVersion, context));
    }

    private InstitutionActorContext context(InstitutionActorIdentityRow identity) {
        InstitutionAuthority authority;
        try {
            authority = store.load(identity.publicId());
        } catch (InstitutionAssignmentStorageException exception) {
            InstitutionActorContext.Failure failure = switch (exception.reason()) {
                case UNCONVERTED -> InstitutionActorContext.Failure.UNCONVERTED;
                case ROLE_MISMATCH -> InstitutionActorContext.Failure.ROLE_MISMATCH;
                case CORRUPT_PAYLOAD -> InstitutionActorContext.Failure.CORRUPT_PAYLOAD;
                default -> throw exception;
            };
            return InstitutionActorContext.invalid(failure);
        }
        boolean matches = identity.role().isOrgTier()
                ? authority.displayRole().filter(role -> role == identity.role()).isPresent()
                : authority.assignments().isEmpty();
        return matches ? InstitutionActorContext.valid(authority)
                : InstitutionActorContext.invalid(InstitutionActorContext.Failure.ACCOUNT_ROLE_MISMATCH);
    }

    /** Verify the caller barrier before a JPA flush or any actor read. */
    public void verifyAdministrationTransaction() { requireLockedCallerTransaction(); }

    private void requireLockedCallerTransaction() {
        requireBoundConnection(Connection.TRANSACTION_READ_COMMITTED);
        Boolean locked = jdbc.queryForObject("""
                select exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
                  and classid=1129074511::oid and objid=1::oid and objsubid=2 and mode='ExclusiveLock' and granted)
                """, Boolean.class);
        if (!Boolean.TRUE.equals(locked)) { throw new IllegalStateException("Acquire the administration barrier before current actor reload"); }
    }

    private void requireSnapshotTransaction() { requireBoundConnection(Connection.TRANSACTION_REPEATABLE_READ); }

    private void requireBoundConnection(int isolation) {
        Object resource = jdbc.getDataSource() == null ? null : TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !(resource instanceof ConnectionHolder holder)) {
            throw new IllegalStateException("Current actor reload requires the caller transaction on its own DataSource");
        }
        try {
            Connection connection = holder.getConnection();
            if (connection.getAutoCommit() || connection.getTransactionIsolation() != isolation) {
                throw new IllegalStateException("Actor read requires its non-autocommit transaction with the specified isolation");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify the caller's actor reload transaction", exception);
        }
    }

    private static int originalVersion(AuthenticatedUser original) {
        if (original.authenticatedTokenVersion() == null) { throw unauthorized(); }
        return original.authenticatedTokenVersion();
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                "인증이 필요합니다", "현재 활성 계정으로 다시 로그인해 주세요.");
    }

    private record InstitutionActorIdentityRow(long id, UUID publicId, String email, UserRole role, String status, int tokenVersion) { }
}
