package br.com.nora.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import br.com.nora.api.infrastructure.speech.SpeechProperties;

@SpringBootApplication
@EnableConfigurationProperties(SpeechProperties.class)
public class NoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoraApiApplication.class, args);
    }
}
