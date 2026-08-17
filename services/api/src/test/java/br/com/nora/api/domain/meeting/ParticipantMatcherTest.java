package br.com.nora.api.domain.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.domain.meeting.ParticipantMatcher.Occurrence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantMatcherTest {

    private final UUID meetingA = UUID.randomUUID();
    private final UUID meetingB = UUID.randomUUID();

    private static Occurrence at(UUID meeting, String name) {
        return new Occurrence(meeting, name, null, false);
    }

    private static Occurrence at(UUID meeting, String name, String email) {
        return new Occurrence(meeting, name, email, false);
    }

    // ---------------------------------------------------------------------------------------
    // normalisation
    // ---------------------------------------------------------------------------------------

    @Test
    void normalisationFoldsAccentsCaseAndWhitespace() {
        assertThat(ParticipantMatcher.normaliseName("  PATRÍCIA   Gonçalves "))
                .isEqualTo("patricia goncalves");
    }

    @Test
    void normalisationDropsHonorificsParticlesAndTrailingAnnotations() {
        assertThat(ParticipantMatcher.normaliseName("Dra. José da Silva (Financeiro)"))
                .isEqualTo("jose silva");
        assertThat(ParticipantMatcher.normaliseName("Maria Ines e Souza"))
                .isEqualTo("maria ines souza");
    }

    @Test
    void anHonorificOnItsOwnStaysTheName() {
        // Stripping it to nothing would make every roster entry reading "Gerente" match every
        // other one, which is the merge this rule exists to avoid.
        assertThat(ParticipantMatcher.normaliseName("Gerente")).isEqualTo("gerente");
    }

    @Test
    void aHyphenatedSurnameIsOneToken() {
        assertThat(ParticipantMatcher.normaliseName("Ana Silva-Costa"))
                .isEqualTo("ana silva-costa");
    }

    // ---------------------------------------------------------------------------------------
    // the matching rules
    // ---------------------------------------------------------------------------------------

    @Test
    void theSamePersonNamedTwoWaysIsOnePerson() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(at(meetingA, "Ana Paula Silva"), at(meetingB, "Ana Silva")));

        assertThat(identities).hasSize(1);
        ParticipantIdentity ana = identities.get(0);
        assertThat(ana.displayName()).isEqualTo("Ana Paula Silva");
        assertThat(ana.variants()).containsExactly("Ana Paula Silva", "Ana Silva");
        assertThat(ana.meetingIds()).containsExactly(meetingA, meetingB);
        assertThat(ana.meetingCount()).isEqualTo(2);
    }

    @Test
    void anEmailOutranksTheName() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(
                                at(meetingA, "Ana Silva", "ana@acme.com"),
                                at(meetingB, "Ana Paula Ribeiro", "ANA@acme.com ")));

        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).email()).isEqualTo("ana@acme.com");
        assertThat(identities.get(0).variants()).containsExactly("Ana Paula Ribeiro", "Ana Silva");
    }

    @Test
    void aDifferingEmailVetoesANameMerge() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(
                                at(meetingA, "Ana Silva", "ana@a.com"),
                                at(meetingB, "Ana Silva", "ana@b.com")));

        assertThat(identities).hasSize(2);
        assertThat(identities).allSatisfy(i -> assertThat(i.meetingCount()).isEqualTo(1));
    }

    @Test
    void anUnlabelledRowUnderTwoEmailsJoinsNeither() {
        // Attaching it to whichever e-mail sorted first would be a coin toss deciding who a
        // person is, so it becomes an identity of its own.
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(
                                at(meetingA, "Ana Silva", "ana@a.com"),
                                at(meetingA, "Ana Silva", "ana@b.com"),
                                at(meetingB, "Ana Silva")));

        assertThat(identities).hasSize(3);
        assertThat(identities).filteredOn(i -> i.email() == null).hasSize(1);
    }

    @Test
    void aLoneFirstNameNeverAbsorbsAFullName() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(List.of(at(meetingA, "Ana"), at(meetingB, "Ana Silva")));

        assertThat(identities).hasSize(2);
    }

    @Test
    void differentSurnamesStayDifferentPeople() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(at(meetingA, "Ana Paula Silva"), at(meetingA, "Ana Paula Costa")));

        assertThat(identities).hasSize(2);
    }

    @Test
    void theCanonicalSpellingCarriesTheLeastDecoration() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(at(meetingA, "Sr. Bruno Dias"), at(meetingB, "Bruno Dias")));

        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).displayName()).isEqualTo("Bruno Dias");
        assertThat(identities.get(0).variants()).containsExactly("Bruno Dias", "Sr. Bruno Dias");
    }

    @Test
    void initialsAreNotExpanded() {
        // "A.P. Silva" folds to three tokens, two of them single letters, so neither the full-name
        // rule nor the first/last pair reaches "Ana Paula Silva". Deliberate: expanding an initial
        // would let one letter decide who somebody is. ADR 0048 §3.
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(at(meetingA, "A.P. Silva"), at(meetingB, "Ana Paula Silva")));

        assertThat(identities).hasSize(2);
    }

    @Test
    void aNicknameIsNotMatched() {
        // The price of a deterministic rule, paid knowingly — ADR 0048 §1 and its debts.
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(at(meetingA, "Bia"), at(meetingA, "Beatriz Souza")));

        assertThat(identities).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------
    // properties the ADR relies on
    // ---------------------------------------------------------------------------------------

    @Test
    void theResultDoesNotDependOnTheOrderTheRowsArriveIn() {
        List<Occurrence> rows =
                new ArrayList<>(
                        List.of(
                                at(meetingA, "Ana Paula Silva", "ana@acme.com"),
                                at(meetingA, "Ana Silva"),
                                at(meetingB, "Bruno Dias"),
                                at(meetingB, "BRUNO DIAS"),
                                at(meetingB, "Carla Nunes", "carla@acme.com")));

        List<String> reference = idsOf(ParticipantMatcher.group(rows));
        for (int seed = 0; seed < 20; seed++) {
            List<Occurrence> shuffled = new ArrayList<>(rows);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(idsOf(ParticipantMatcher.group(shuffled)))
                    .containsExactlyInAnyOrderElementsOf(reference);
        }
    }

    @Test
    void theIdIsStableWhenALaterMeetingAddsAMiddleName() {
        List<ParticipantIdentity> before =
                ParticipantMatcher.group(List.of(at(meetingA, "Ana Silva")));
        List<ParticipantIdentity> after =
                ParticipantMatcher.group(
                        List.of(at(meetingA, "Ana Silva"), at(meetingB, "Ana Paula Silva")));

        assertThat(after).hasSize(1);
        assertThat(after.get(0).id()).isEqualTo(before.get(0).id());
    }

    @Test
    void theIdDoesNotSpellTheNameOut() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(List.of(at(meetingA, "Ana Silva")));

        // "n" is not a hex character, so "ana" cannot occur by accident either.
        assertThat(identities.get(0).id()).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void internalIsTrueWhenAnyOccurrenceDeclaredIt() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.group(
                        List.of(
                                new Occurrence(meetingA, "Ana Silva", null, false),
                                new Occurrence(meetingB, "Ana Paula Silva", null, true)));

        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).internal()).isTrue();
    }

    @Test
    void dedupeCollapsesOneMeetingsRoster() {
        List<ParticipantIdentity> identities =
                ParticipantMatcher.dedupe(
                        List.of(
                                new Participant("Ana Paula Silva", null, true),
                                new Participant("ana paula silva", null, false),
                                new Participant("Bruno Dias", null, false)));

        assertThat(identities).hasSize(2);
        assertThat(identities.get(0).displayName()).isEqualTo("Ana Paula Silva");
    }

    @Test
    void anEmptyRosterIsAnEmptyResult() {
        assertThat(ParticipantMatcher.group(List.of())).isEmpty();
        assertThat(ParticipantMatcher.dedupe(List.of())).isEmpty();
    }

    private static List<String> idsOf(List<ParticipantIdentity> identities) {
        return identities.stream().map(ParticipantIdentity::id).toList();
    }
}
