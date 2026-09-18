package kr.ac.pusan.pickle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PickleApiApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PickleApiApplication.class, args);
        closeAfterOneShot(context);
    }

    static void closeAfterOneShot(ConfigurableApplicationContext context) {
        if (context.getEnvironment().acceptsProfiles(Profiles.of("isolated-bootstrap"))) {
            SpringApplication.exit(context);
        }
    }
}
