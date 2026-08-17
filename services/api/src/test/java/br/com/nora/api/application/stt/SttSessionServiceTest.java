package br.com.nora.api.application.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.application.stt.ports.RealtimeSttBroker;
import br.com.nora.api.infrastructure.stt.SttProperties;
import br.com.nora.api.infrastructure.stt.SttRateLimiter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the session-minting service. No Spring, no Docker: the point of this class is the
 * ORDER of the three things it does (limit, resolve, record) and the honesty of what it records.
 */
class SttSessionServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

    /** Records every call so the test can assert on the arguments, including the tenant id. */
    private static final class RecordingUsageRecorder implements UsageRecorder {

        record External(
                String service,
                String provider,
                String model,
                UUID tenantId,
                int promptTokens,
                int completionTokens,
                BigDecimal costUsdHint,
                Integer latencyMs,
                String status) {}

        final List<External> calls = new ArrayList<>();

        @Override
        public void recordAnalysisUsage(
                UUID tenantId,
                String modelVersion,
                int promptTokens,
                int completionTokens,
                Integer latencyMs,
                boolean stub) {
            throw new AssertionError("the STT path must not report itself as analysis usage");
        }

        @Override
        public void recordExternal(
                String service,
                String provider,
                String model,
                UUID tenantId,
                int promptTokens,
                int completionTokens,
                BigDecimal costUsdHint,
                Integer latencyMs,
                String status) {
            calls.add(
                    new External(
                            service,
                            provider,
                            model,
                            tenantId,
                            promptTokens,
                            completionTokens,
                            costUsdHint,
                            latencyMs,
                            status));
        }
    }

    private static final class StubBroker implements RealtimeSttBroker {

        String lastLanguage;
        int calls;
        RuntimeException failure;

        @Override
        public RealtimeSttSession openSession(String language) {
            calls++;
            lastLanguage = language;
            if (failure != null) {
                throw failure;
            }
            return new RealtimeSttSession(
                    "ek_secret",
                    Instant.parse("2026-08-17T12:00:00Z"),
                    "wss://provider.example/realtime",
                    "openai",
                    "gpt-live-transcribe",
                    language,
                    "audio/pcm",
                    24_000);
        }

        @Override
        public String provider() {
            return "openai";
        }

        @Override
        public String model() {
            return "gpt-live-transcribe";
        }
    }

    private static SttProperties props(int sessionsPerMinute) {
        return new SttProperties("pt", null, new SttProperties.RateLimit(sessionsPerMinute));
    }

    @Test
    void narrowsTheDesktopsBcp47TagToThePrimarySubtag() {
        StubBroker broker = new StubBroker();
        SttSessionService service = service(broker, props(10), new RecordingUsageRecorder());

        service.openSession(USER, TENANT, "pt-BR");

        assertThat(broker.lastLanguage).isEqualTo("pt");
    }

    @Test
    void fallsBackToTheConfiguredLanguageRatherThanFailingTheRecording() {
        StubBroker broker = new StubBroker();
        SttSessionService service = service(broker, props(10), new RecordingUsageRecorder());

        service.openSession(USER, TENANT, "  ");
        assertThat(broker.lastLanguage).isEqualTo("pt");

        service.openSession(USER, TENANT, "123");
        assertThat(broker.lastLanguage).isEqualTo("pt");
    }

    /**
     * The limiter guards a paid provider, so it has to fire BEFORE the broker call. Asserting the
     * exception alone would pass even if the session had already been created and paid for.
     */
    @Test
    void consumesTheRateLimitBeforeSpendingMoney() {
        StubBroker broker = new StubBroker();
        SttSessionService service = service(broker, props(1), new RecordingUsageRecorder());

        service.openSession(USER, TENANT, "pt-BR");

        assertThatThrownBy(() -> service.openSession(USER, TENANT, "pt-BR"))
                .isInstanceOf(SttException.RateLimited.class);
        assertThat(broker.calls).as("the refused call must not reach the provider").isEqualTo(1);
    }

    /** The budget is per user: one caller exhausting it must not block another. */
    @Test
    void theBudgetIsPerUser() {
        StubBroker broker = new StubBroker();
        SttSessionService service = service(broker, props(1), new RecordingUsageRecorder());

        service.openSession(USER, TENANT, "pt-BR");
        service.openSession(UUID.randomUUID(), TENANT, "pt-BR");

        assertThat(broker.calls).isEqualTo(2);
    }

    /**
     * The whole attribution story of ADR 0039, asserted: the row carries the caller's tenant, it is
     * filed under the external service {@code stt}, and it claims NO token counts and NO cost —
     * because the audio never crossed this infrastructure and a number invented from a
     * client-reported duration is exactly what the ADR forbids.
     */
    @Test
    void recordsTheIssuanceAgainstTheCallersTenantAndClaimsNoUsageItCannotSee() {
        RecordingUsageRecorder usage = new RecordingUsageRecorder();
        SttSessionService service = service(new StubBroker(), props(10), usage);

        service.openSession(USER, TENANT, "pt-BR");

        assertThat(usage.calls).hasSize(1);
        RecordingUsageRecorder.External call = usage.calls.get(0);
        assertThat(call.service()).isEqualTo("stt");
        assertThat(call.provider()).isEqualTo("openai");
        assertThat(call.model()).isEqualTo("gpt-live-transcribe");
        assertThat(call.tenantId()).isEqualTo(TENANT);
        assertThat(call.promptTokens()).isZero();
        assertThat(call.completionTokens()).isZero();
        assertThat(call.costUsdHint()).isNull();
        assertThat(call.status()).isEqualTo("ok");
    }

    /** A tenant whose sessions are all being refused is what the console most needs to see. */
    @Test
    void recordsAFailedIssuanceToo() {
        RecordingUsageRecorder usage = new RecordingUsageRecorder();
        StubBroker broker = new StubBroker();
        broker.failure = new SttException.NotConfigured();
        SttSessionService service = service(broker, props(10), usage);

        assertThatThrownBy(() -> service.openSession(USER, TENANT, "pt-BR"))
                .isInstanceOf(SttException.NotConfigured.class);

        assertThat(usage.calls).hasSize(1);
        assertThat(usage.calls.get(0).status()).isEqualTo("error");
        assertThat(usage.calls.get(0).tenantId()).isEqualTo(TENANT);
    }

    /**
     * A record's generated toString prints every component. This session carries a live credential,
     * so one interpolation into a log line would publish it. The override is load-bearing, and a
     * component added later would silently remove it.
     */
    @Test
    void theSessionNeverPrintsItsCredential() {
        RealtimeSttSession session =
                new RealtimeSttSession(
                        "ek_supersecret",
                        Instant.EPOCH,
                        "wss://provider.example/realtime",
                        "openai",
                        "gpt-live-transcribe",
                        "pt",
                        "audio/pcm",
                        24_000);

        assertThat(session.toString()).doesNotContain("ek_supersecret").contains("<redacted>");
    }

    private static SttSessionService service(
            RealtimeSttBroker broker, SttProperties props, UsageRecorder usage) {
        return new SttSessionService(broker, new SttRateLimiter(props), props, usage);
    }
}
