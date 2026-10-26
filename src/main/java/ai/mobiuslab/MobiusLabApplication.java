package ai.mobiuslab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point of the Möbius Lab self-evolving research agent platform backend. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MobiusLabApplication {

    /** Boots the Spring application. */
    public static void main(String[] args) {
        SpringApplication.run(MobiusLabApplication.class, args);
    }
}
