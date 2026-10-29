package kr.ac.pusan.pickle.security.institution;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Strict explicit assignment storage. It never authorizes, derives a payload from a role,
 * or repairs a missing/corrupt source. Membership, derived role, roster epoch and audit
 * changes belong to the authorized atomic writer; save changes only an existing matching-role row.
 */
public final class InstitutionAssignmentStore {
    private static final String ROWS = """
            select r.user_id,r.org_id,u.public_id as account_public_id,o.public_id as institution_public_id,
                   r.role::text as row_role,r.permission_mode,r.permission_payload::text as permission_payload,
                   r.permission_revision
              from user_org_roles r join users u on u.id=r.user_id join orgs o on o.id=r.org_id
            """;
    private static final RowMapper<StoredRow> ROW_MAPPER = (rs, index) -> new StoredRow(
            rs.getLong("user_id"), rs.getLong("org_id"), rs.getObject("account_public_id", UUID.class),
            rs.getObject("institution_public_id", UUID.class), UserRole.valueOf(rs.getString("row_role")),
            rs.getString("permission_mode"), rs.getString("permission_payload"),
            rs.getObject("permission_revision", Long.class));

    private final JdbcTemplate jdbc;
    private final InstitutionAssignmentCodec codec;

    public InstitutionAssignmentStore(JdbcTemplate jdbc, InstitutionAssignmentCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public Optional<InstitutionAssignment> find(UUID accountId, UUID institutionId) {
        return rows(accountId, institutionId, false).stream().findFirst().map(this::decode);
    }

    /** Every row comes from one statement snapshot; an unconverted row fails the whole read. */
    public InstitutionAuthority load(UUID accountId) {
        List<InstitutionAssignment> assignments = jdbc.query(ROWS + " where u.public_id=? order by o.public_id",
                ROW_MAPPER, accountId).stream().map(this::decode).toList();
        return new InstitutionAuthority(accountId, assignments);
    }

    /**
     * Explicitly stores only permission fields on an already existing, matching-role membership.
     * expectedRevision=0 names an unconverted row; the input revision never becomes authoritative.
     */
    public InstitutionAssignment save(InstitutionAssignment desired, long expectedRevision) {
        requireStoreTransaction();
        if (expectedRevision < 0) { throw new IllegalArgumentException("expectedRevision must not be negative"); }
        StoredRow before = rows(desired.accountId(), desired.institutionId(), true).stream().findFirst()
                .orElseThrow(() -> new InstitutionAssignmentStorageException(
                        InstitutionAssignmentStorageException.Reason.MISSING_MEMBERSHIP,
                        "Institution membership must already exist"));
        long currentRevision;
        if (before.mode() == null && before.payload() == null && before.revision() == null) {
            currentRevision = 0;
        } else {
            currentRevision = decode(before).revision();
        }
        if (currentRevision != expectedRevision) {
            throw new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.REVISION_CONFLICT,
                    "Institution assignment revision has changed");
        }
        InstitutionAssignmentCodec.requireRole(desired, before.role());
        InstitutionAssignmentCodec.Encoded encoded = codec.encode(desired);
        Long revision = jdbc.queryForObject("""
                update user_org_roles set permission_mode=?,permission_payload=?::jsonb,
                       permission_revision=nextval('institution_permission_revision_seq')
                 where user_id=? and org_id=? returning permission_revision
                """, Long.class, encoded.mode(), encoded.payload(), before.userId(), before.orgId());
        return codec.decode(before.accountId(), before.institutionId(), before.role(), encoded.mode(), encoded.payload(), revision);
    }

    private void requireStoreTransaction() {
        Object resource = jdbc.getDataSource() == null ? null
                : TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !(resource instanceof ConnectionHolder holder)) {
            throw transactionRequired();
        }
        // Another DataSource's synchronization can auto-bind an autocommit connection.
        // JdbcTemplate must use this already-bound, non-autocommit connection owned by the caller.
        boolean autoCommit;
        try {
            autoCommit = holder.getConnection().getAutoCommit();
        } catch (SQLException | IllegalStateException exception) {
            throw new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.TRANSACTION_REQUIRED,
                    "Institution assignment storage cannot verify its bound transaction", exception);
        }
        if (autoCommit) { throw transactionRequired(); }
    }

    private static InstitutionAssignmentStorageException transactionRequired() {
        return new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.TRANSACTION_REQUIRED,
                "Institution assignment storage requires the caller's transaction on its own DataSource");
    }

    private List<StoredRow> rows(UUID accountId, UUID institutionId, boolean lock) {
        return jdbc.query(ROWS + " where u.public_id=? and o.public_id=?" + (lock ? " for update of r" : ""),
                ROW_MAPPER, accountId, institutionId);
    }

    private InstitutionAssignment decode(StoredRow row) {
        return codec.decode(row.accountId(), row.institutionId(), row.role(), row.mode(), row.payload(), row.revision());
    }

    private record StoredRow(long userId, long orgId, UUID accountId, UUID institutionId, UserRole role,
            @Nullable String mode, @Nullable String payload, @Nullable Long revision) { }
}
