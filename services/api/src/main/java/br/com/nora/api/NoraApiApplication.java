package br.com.nora.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @EnableAsync was moved to AsyncConfig (which defines the named TaskExecutor bean).
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "br.com.nora.api")
public class NoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoraApiApplication.class, args);
    }
}
