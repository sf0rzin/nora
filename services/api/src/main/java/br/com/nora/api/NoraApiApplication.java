package br.com.nora.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @EnableAsync foi movido para AsyncConfig (que define o TaskExecutor bean nominado).
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "br.com.nora.api")
public class NoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoraApiApplication.class, args);
    }
}
