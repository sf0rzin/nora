package br.com.nora.api;

import br.com.nora.api.infrastructure.speech.SpeechProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SpeechProperties.class)
public class NoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoraApiApplication.class, args);
    }
}
