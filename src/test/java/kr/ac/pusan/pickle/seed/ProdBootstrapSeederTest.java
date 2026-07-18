package kr.ac.pusan.pickle.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.ac.pusan.pickle.auth.PasswordPolicy;
import kr.ac.pusan.pickle.group.PersonalGroupService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Prod bootstrap seeder (contract v0.9.0 ops readiness). Verifies the single
 * fail-fast-guarded SYS_ADMIN creation, idempotent no-op when one exists, and
 * refusal to start on missing/blank/placeholder credentials. Pure unit test —
 * the seeded env varies per case, and the test-profile DB already holds a
 * SYS_ADMIN, so mocking gives deterministic control over the empty/non-empty
 * states.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProdBootstrapSeederTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PersonalGroupService personalGroupService;

    /** Real policy (no deps): exercises the same weak-password bar as signup. */
    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    private ProdBootstrapSeeder seederWith(MockEnvironment env) {
        return new ProdBootstrapSeeder(userRepository, passwordEncoder, personalGroupService,
                passwordPolicy, env);
    }

    private static MockEnvironment validEnv() {
        return new MockEnvironment()
                .withProperty(ProdBootstrapSeeder.EMAIL_ENV, "root-admin@pickle.local")
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "S3cure-Bootstrap-Pw!");
    }

    @Test
    void createsSingleVerifiedSysAdminWhenNoneExists() {
        when(userRepository.findByRole(UserRole.SYS_ADMIN)).thenReturn(List.of());
        when(userRepository.existsByEmail("root-admin@pickle.local")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        seederWith(validEnv()).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getEmail()).isEqualTo("root-admin@pickle.local");
        assertThat(admin.getRole()).isEqualTo(UserRole.SYS_ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.getEmailVerifiedAt()).isNotNull();
        verify(personalGroupService).ensurePersonalGroup(any(User.class));
    }

    @Test
    void isIdempotentNoOpWhenSysAdminAlreadyExists() {
        when(userRepository.findByRole(UserRole.SYS_ADMIN))
                .thenReturn(List.of(new User("existing@pickle.local", "h", "관리자")));

        seederWith(validEnv()).run(null);

        verify(userRepository, never()).save(any());
        verify(personalGroupService, never()).ensurePersonalGroup(any());
    }

    @Test
    void refusesWhenEmailMissing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "S3cure-Bootstrap-Pw!");
        assertThatThrownBy(() -> seederWith(env).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ProdBootstrapSeeder.EMAIL_ENV);
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesWhenPasswordBlank() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(ProdBootstrapSeeder.EMAIL_ENV, "root-admin@pickle.local")
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "   ");
        assertThatThrownBy(() -> seederWith(env).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ProdBootstrapSeeder.PASSWORD_ENV);
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesPlaceholderPassword() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(ProdBootstrapSeeder.EMAIL_ENV, "root-admin@pickle.local")
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "changeme");
        assertThatThrownBy(() -> seederWith(env).run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesWeakButLongPassword() {
        // Long enough to clear the length floor and not in the exact blacklist,
        // but structurally weak — PasswordPolicy must still reject it so a
        // guessable admin never bootstraps.
        MockEnvironment env = new MockEnvironment()
                .withProperty(ProdBootstrapSeeder.EMAIL_ENV, "root-admin@pickle.local")
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "aaaaaaaaaaaa");
        assertThatThrownBy(() -> seederWith(env).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ProdBootstrapSeeder.PASSWORD_ENV);
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesDevSeedDefaultPassword() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(ProdBootstrapSeeder.EMAIL_ENV, "root-admin@pickle.local")
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "pickle-sysadmin-dev!");
        assertThatThrownBy(() -> seederWith(env).run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any());
    }
}
