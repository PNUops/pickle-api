package kr.ac.pusan.pickle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PickleApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PickleApiApplication.class, args);
    }

}
