package br.com.nora.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "br.com.nora.api")
@EnableAsync
public class NoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoraApiApplication.class, args);
    }
}
