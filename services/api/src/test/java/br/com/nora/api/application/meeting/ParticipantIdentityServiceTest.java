package br.com.nora.api.application.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.meeting.ParticipantIdentityService.IdentityView;
import br.com.nora.api.application.meeting.ParticipantIdentityService.MeetingRef;
import br.com.nora.api.application.ports.ParticipantRepository;
import br.com.nora.api.application.ports.ParticipantRepository.ParticipantOccurrence;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantIdentityServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID older = UUID.randomUUID();
    private final UUID newer = UUID.randomUUID();

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T10:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-06-01T10:00:00Z");

    /** Rows exactly as the adapter returns them: most recent meeting first. */
    private ParticipantIdentityService serviceWithRows() {
        List<ParticipantOccurrence> rows =
                List.of(
                        occurrence(newer, "Follow-up", T2, "Ana Silva"),
                        occurrence(older, "Discovery", T1, "Ana Paula Silva"),
                        occurrence(older, "Discovery", T1, "Bruno Dias"));
        return new ParticipantIdentityService(new FakeRepository(rows));
    }

    @Test
    void groupsAcrossMeetingsAndReportsTheRangeItSpans() {
        List<IdentityView> views = serviceWithRows().list(Scope.wholeTenant(tenant));

        assertThat(views).hasSize(2);
        IdentityView ana = views.get(0);
        assertThat(ana.identity().displayName()).isEqualTo("Ana Paula Silva");
        assertThat(ana.identity().meetingCount()).isEqualTo(2);
        assertThat(ana.firstSeenAt()).isEqualTo(T1);
        assertThat(ana.lastSeenAt()).isEqualTo(T2);
        // Most recent meeting first inside the identity too.
        List<UUID> ids = ana.meetings().stream().map(MeetingRef::id).toList();
        assertThat(ids).containsExactly(newer, older);
    }

    @Test
    void theMostPresentPersonComesFirst() {
        List<IdentityView> views = serviceWithRows().list(Scope.wholeTenant(tenant));
        assertThat(views.stream().map(v -> v.identity().displayName()).toList())
                .containsExactly("Ana Paula Silva", "Bruno Dias");
    }

    @Test
    void anEmptyScopeIsNothingAndNeverWidensToTheTenant() {
        // The distinction the Scope record exists to make explicit: a caller allowed to open no
        // meeting must see nobody, which is not the same as "no restriction".
        FakeRepository repo =
                new FakeRepository(List.of(occurrence(older, "Discovery", T1, "Ana Silva")));
        ParticipantIdentityService service = new ParticipantIdentityService(repo);

        assertThat(service.list(Scope.ofMeetings(tenant, List.of()))).isEmpty();
        assertThat(service.list(Scope.ofMeetings(tenant, List.of(older)))).hasSize(1);
        assertThat(repo.scopes).hasSize(2);
    }

    private static ParticipantOccurrence occurrence(
            UUID meetingId, String title, OffsetDateTime startedAt, String displayName) {
        return new ParticipantOccurrence(meetingId, title, startedAt, displayName, null, false);
    }

    /** Honours the scope the way the SQL does, so the service is tested and not the double. */
    private static final class FakeRepository implements ParticipantRepository {

        private final List<ParticipantOccurrence> rows;
        private final List<Scope> scopes = new ArrayList<>();

        FakeRepository(List<ParticipantOccurrence> rows) {
            this.rows = rows;
        }

        @Override
        public List<ParticipantOccurrence> listOccurrences(Scope scope) {
            scopes.add(scope);
            if (!scope.restricted()) {
                return rows;
            }
            return rows.stream().filter(r -> scope.meetingIds().contains(r.meetingId())).toList();
        }
    }
}
