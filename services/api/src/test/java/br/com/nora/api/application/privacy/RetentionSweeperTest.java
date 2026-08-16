package br.com.nora.api.application.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Retention sentinel semantics. The value is an age in days and the OFF switch is at the bottom of
 * the range — {@code 0} and any negative value mean "never purge", NOT "purge immediately". This
 * test is what keeps that from being flipped by accident: flipping it turns an environment that
 * forgot the variable into one that hard-deletes every meeting on the next cron.
 */
class RetentionSweeperTest {

    private final PrivacyService privacy = mock(PrivacyService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantRlsContext rlsContext = mock(TenantRlsContext.class);

    @Test
    void zeroMeansOff_nothingIsTouched() {
        RetentionSweeper sweeper = sweeper(0);
        assertThat(sweeper.enabled()).isFalse();
        sweeper.sweep();
        verifyNoInteractions(tenants, privacy, rlsContext);
    }

    @Test
    void negativeMeansOff_nothingIsTouched() {
        RetentionSweeper sweeper = sweeper(-1);
        assertThat(sweeper.enabled()).isFalse();
        sweeper.sweep();
        verifyNoInteractions(tenants, privacy, rlsContext);
    }

    @Test
    void positiveWindowPurgesEveryTenantOlderThanTheCutoff() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(tenants.allActiveTenantIds()).thenReturn(List.of(a, b));
        when(privacy.purgeOlderThan(any(), any())).thenReturn(1);

        OffsetDateTime before = OffsetDateTime.now();
        sweeper(30).sweep();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(privacy).purgeOlderThan(eq(a), cutoff.capture());
        verify(privacy).purgeOlderThan(eq(b), cutoff.capture());
        for (OffsetDateTime captured : cutoff.getAllValues()) {
            assertThat(captured).isBefore(before.minusDays(29));
        }
    }

    /**
     * Under RLS enforce the scheduler thread has no JWT: without {@code set()} the purge sees zero
     * rows and the job reports success forever. The {@code clear()} matters just as much — the pool
     * thread is reused by the next tenant.
     */
    @Test
    void wrapsEachTenantInItsOwnRlsScope() {
        UUID a = UUID.randomUUID();
        when(tenants.allActiveTenantIds()).thenReturn(List.of(a));

        sweeper(7).sweep();

        InOrder order = inOrder(rlsContext, privacy);
        order.verify(rlsContext).set(a);
        order.verify(privacy).purgeOlderThan(eq(a), any());
        order.verify(rlsContext).clear();
    }

    /** One tenant blowing up must not stop the others — the sweep is best-effort per tenant. */
    @Test
    void oneFailingTenantDoesNotStopTheRest() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(tenants.allActiveTenantIds()).thenReturn(List.of(a, b));
        when(privacy.purgeOlderThan(eq(a), any())).thenThrow(new IllegalStateException("boom"));

        sweeper(15).sweep();

        verify(privacy).purgeOlderThan(eq(b), any());
        verify(rlsContext, times(2)).clear();
    }

    private RetentionSweeper sweeper(int retentionDays) {
        return new RetentionSweeper(privacy, tenants, rlsContext, retentionDays);
    }
}
