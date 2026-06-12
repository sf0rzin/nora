package br.com.nora.api.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GoogleWorkspaceClientTest {

    @Test
    void mime_temDestinatarioSubjectCodificadoECorpoBase64() {
        String mime =
                GoogleWorkspaceClient.buildMime(
                        "ana@empresa.com", "Reunião analisada — Açaí & Cia", "<p>Olá</p>");

        assertThat(mime).startsWith("To: ana@empresa.com\r\n");
        assertThat(mime).contains("Content-Type: text/html; charset=UTF-8");

        // Subject em RFC 2047 (B-encoding) por causa dos acentos.
        String expectedSubject =
                Base64.getEncoder()
                        .encodeToString(
                                "Reunião analisada — Açaí & Cia".getBytes(StandardCharsets.UTF_8));
        assertThat(mime).contains("Subject: =?UTF-8?B?" + expectedSubject + "?=");

        // Corpo em base64 decodável de volta pro HTML original.
        String body = mime.substring(mime.indexOf("\r\n\r\n") + 4).replaceAll("\\s", "");
        assertThat(new String(Base64.getMimeDecoder().decode(body), StandardCharsets.UTF_8))
                .isEqualTo("<p>Olá</p>");
    }

    @Test
    void rfc3339_mantemSegundosZeradosExigidosPeloCalendar() {
        // OffsetDateTime.toString() omitiria os segundos ("10:00-03:00") e o
        // Calendar rejeita com 400.
        OffsetDateTime semSegundos =
                OffsetDateTime.of(2026, 6, 13, 10, 0, 0, 0, ZoneOffset.ofHours(-3));

        assertThat(GoogleWorkspaceClient.rfc3339(semSegundos))
                .isEqualTo("2026-06-13T10:00:00-03:00");
    }

    @Test
    void rfc3339_emUtcUsaOffsetNumerico() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 6, 13, 13, 30, 0, 0, ZoneOffset.UTC);

        assertThat(GoogleWorkspaceClient.rfc3339(utc)).isEqualTo("2026-06-13T13:30:00+00:00");
    }
}
