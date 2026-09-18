package kr.ac.pusan.pickle.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.ac.pusan.pickle.auth.PasswordPolicy;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.PersonalWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminServiceTest {

    private static final List<String> TABLES = List.of(
            "audit_logs", "nodes", "users", "workspaces", "workspace_members");

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PersonalWorkspaceService personalWorkspaceService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private EntityManager entityManager;

    private BootstrapAdminService service;

    @BeforeEach
    void setUp() {
        service = new BootstrapAdminService(userRepository, passwordEncoder,
                personalWorkspaceService, new PasswordPolicy(), jdbcTemplate, entityManager);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
    }

    @Test
    void isolatedBootstrapCreatesOnlyVerifiedAdminAndPersonalWorkspace() {
        AtomicBoolean flushed = new AtomicBoolean();
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(TABLES);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!flushed.get()) {
                return 0L;
            }
            return sql.contains("\"users\"") || sql.contains("\"workspaces\"")
                    || sql.contains("\"workspace_members\"") ? 1L : 0L;
        });
        doAnswer(invocation -> {
            flushed.set(true);
            return null;
        }).when(entityManager).flush();
        when(userRepository.existsByEmail("admin@example.test")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrapIsolated(" admin@example.test ", "S3cure-Bootstrap-Pw!");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.SYS_ADMIN);
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getValue().getEmailVerifiedAt()).isNotNull();
        assertThat(saved.getValue().getPosition().name()).isEqualTo("STAFF");
        verify(personalWorkspaceService).ensurePersonalWorkspace(saved.getValue());
        verify(jdbcTemplate).execute(anyString());
    }

    @Test
    void isolatedBootstrapRefusesAnyExistingApplicationData() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(TABLES);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("\"audit_logs\"") ? 1L : 0L);

        assertThatThrownBy(() -> service.bootstrapIsolated(
                "admin@example.test", "S3cure-Bootstrap-Pw!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit_logs");

        verify(userRepository, never()).save(any());
        verify(personalWorkspaceService, never()).ensurePersonalWorkspace(any());
    }

    @Test
    void isolatedBootstrapRefusesChangedSchemaDefinedUpstreams() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        assertThatThrownBy(() -> service.bootstrapIsolated(
                "admin@example.test", "S3cure-Bootstrap-Pw!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM upstream");

        verify(userRepository, never()).save(any());
    }
}
