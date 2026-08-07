package kr.ac.pusan.pickle.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * The database password has no committed default, so this check is what turns a
 * forgotten {@code PICKLE_DB_PASSWORD} into a refusal to start. Nothing else
 * fails on it: {@code @ConfigurationProperties} binding leaves an unresolvable
 * placeholder in place, and the connection attempt that follows reads as a
 * wrong password rather than a missing variable.
 */
class DataSourceCredentialCheckTest {

    @Test
    void passesWhenThePasswordIsSet() {
        assertThatCode(() -> check("a-real-password")).doesNotThrowAnyException();
    }

    @Test
    void refusesWhenThePasswordIsAbsent() {
        assertThatThrownBy(() -> check(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PICKLE_DB_PASSWORD");
    }

    @Test
    void refusesWhenThePasswordIsBlank() {
        assertThatThrownBy(() -> check("   ")).isInstanceOf(IllegalStateException.class);
    }

    /**
     * An unset variable leaves the placeholder text as the value; a password
     * that itself contains {@code ${...}} is rejected for the same reason,
     * which is why the message names both cases instead of only the missing
     * variable.
     */
    @Test
    void refusesAValueThatCarriesAPlaceholder() {
        assertThatThrownBy(() -> check("abc${weird}def"))
                .isInstanceOfSatisfying(IllegalStateException.class,
                        ex -> assertThat(ex).hasMessageContaining("${"));
    }

    private void check(String password) {
        MockEnvironment environment = new MockEnvironment();
        if (password != null) {
            environment.setProperty(DataSourceCredentialCheck.PROPERTY, password);
        }
        DataSourceCredentialCheck.requireDataSourcePassword(environment)
                .postProcessBeanFactory(new DefaultListableBeanFactory());
    }
}
