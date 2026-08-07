package kr.ac.pusan.pickle.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails startup when the database password is missing, the same way the JWT
 * secret and the credential-encryption key do.
 *
 * <p>The check is needed because {@code @ConfigurationProperties} binding does
 * not throw on an unresolvable {@code ${...}}: it leaves the placeholder text
 * in place, so an unset {@code PICKLE_DB_PASSWORD} reaches the driver as the
 * literal string and surfaces as an authentication failure against the
 * database, which reads as a wrong password rather than a missing variable.
 * Running as a {@link BeanFactoryPostProcessor} puts it ahead of Flyway and the
 * connection pool, so the message the operator sees is the first one.
 */
@Configuration
public class DataSourceCredentialCheck {

    static final String PROPERTY = "spring.datasource.password";

    @Bean
    static BeanFactoryPostProcessor requireDataSourcePassword(Environment environment) {
        return new Check(environment);
    }

    private record Check(Environment environment) implements BeanFactoryPostProcessor {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            String password;
            try {
                password = environment.getProperty(PROPERTY);
            } catch (IllegalArgumentException unresolvable) {
                // Reached both when the variable is absent and when a password
                // that is present contains ${...}, which the environment tries
                // to resolve as a nested placeholder wherever it appears. The
                // two are indistinguishable here, so the message names both
                // rather than asserting the variable is missing.
                throw unusable(unresolvable);
            }
            if (password == null || password.isBlank() || password.contains("${")) {
                throw unusable(null);
            }
        }

        private static IllegalStateException unusable(Throwable cause) {
            return new IllegalStateException(PROPERTY + " is not usable. Provide PICKLE_DB_PASSWORD via "
                    + "/etc/pickle/api.env; the value must be non-blank and must not contain '${'.", cause);
        }
    }
}
