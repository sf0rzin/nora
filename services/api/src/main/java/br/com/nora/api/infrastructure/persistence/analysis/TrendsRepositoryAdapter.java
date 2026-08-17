package br.com.nora.api.infrastructure.persistence.analysis;

import br.com.nora.api.application.ports.TrendsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL behind the trends panel (US21). Native queries, because every one of them is a GROUP BY over
 * a projection no entity models, and loading aggregates through JPA would mean loading the rows.
 *
 * <p><b>Bucketing is done by the database, in the reporting zone.</b> {@code timezone(zone, ts)}
 * turns the {@code TIMESTAMPTZ} into local wall-clock time and {@code date_trunc} then cuts it, so
 * a meeting late on a Friday evening lands in the week the user thinks it did. {@code date_trunc}
 * with {@code week} is ISO — buckets start on Monday, which is what {@code TrendsService} mirrors
 * when it fills the gaps.
 *
 * <p><b>Casts, not the double-colon operator.</b> Hibernate scans a native query for named
 * parameters, and a Postgres cast written with two colons is two colons where it looks for one.
 * Every cast here is spelled {@code CAST(x AS t)} for that reason — the same reason the
 * neighbouring {@code TaskRepositoryAdapter} casts its nullable binds.
 *
 * <p><b>The meeting restriction is an array, not an expanded IN list.</b> {@code Scope} carries the
 * meetings the caller may open, and on the per-item authorization path that list is the size of the
 * tenant. An expanded {@code IN} would put one JDBC parameter per meeting and break past 32767 of
 * them; {@code = ANY(CAST(? AS uuid[]))} is one bind at any size. The empty array matches nothing,
 * which is exactly right: a caller who may open no meeting must see zeros and never the tenant.
 *
 * <p><b>Soft-deleted meetings are excluded everywhere.</b> Native SQL bypasses the restriction the
 * entity declares, and the per-item path builds its id list from that entity — so without the
 * explicit predicate the tenant-wide path would count meetings the filtered path cannot, and the
 * two would disagree by exactly the deleted ones.
 */
@Repository
public class TrendsRepositoryAdapter implements TrendsRepository {

    @PersistenceContext private EntityManager em;

    /** Joins an action item to the meeting it came from — the unit authorization is decided on. */
    private static final String ACTION_ITEM_SOURCE =
            "FROM meeting_action_items ai "
                    + "JOIN meeting_analyses a ON a.id = ai.analysis_id "
                    + "JOIN meetings m ON m.id = a.meeting_id AND m.deleted_at IS NULL ";

    /** A meeting's position on the time axis: when it happened, falling back to when it arrived. */
    private static final String MEETING_INSTANT = "COALESCE(m.started_at, m.created_at)";

    private static final String MEETING_SOURCE =
            "FROM meetings m JOIN meeting_analyses an ON an.meeting_id = m.id ";

    private static final String MEETING_IN_WINDOW =
            "  AND COALESCE(m.started_at, m.created_at) >= :windowFrom "
                    + "  AND COALESCE(m.started_at, m.created_at) < :windowTo";

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<BucketCount> openedPerBucket(Scope scope, Window window) {
        String bucket = bucketOf("ai.created_at");
        String sql =
                "SELECT "
                        + bucket
                        + " AS bucket, COUNT(*) "
                        + ACTION_ITEM_SOURCE
                        + "WHERE ai.tenant_id = :tenantId "
                        + "  AND ai.created_at >= :windowFrom "
                        + "  AND ai.created_at < :windowTo"
                        + meetingRestriction(scope)
                        + " GROUP BY 1 ORDER BY 1";
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        bindBucketing(query, window);
        bindRange(query, window);
        return toBuckets((List<Object[]>) query.getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<BucketCount> completedPerBucket(Scope scope, Window window) {
        // completed_at is NULL for everything that is not DONE, and NULL fails both comparisons,
        // so the range filter is also the "is it finished?" filter.
        String bucket = bucketOf("ai.completed_at");
        String sql =
                "SELECT "
                        + bucket
                        + " AS bucket, COUNT(*) "
                        + ACTION_ITEM_SOURCE
                        + "WHERE ai.tenant_id = :tenantId "
                        + "  AND ai.completed_at >= :windowFrom "
                        + "  AND ai.completed_at < :windowTo"
                        + meetingRestriction(scope)
                        + " GROUP BY 1 ORDER BY 1";
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        bindBucketing(query, window);
        bindRange(query, window);
        return toBuckets((List<Object[]>) query.getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<OpenByPriority> openBacklog(Scope scope, LocalDate today) {
        String sql =
                "SELECT ai.priority, COUNT(*), "
                        + "COUNT(*) FILTER (WHERE ai.due_date IS NOT NULL "
                        + "AND ai.due_date < CAST(:today AS date)) "
                        + ACTION_ITEM_SOURCE
                        + "WHERE ai.tenant_id = :tenantId "
                        + "  AND ai.status <> 'DONE'"
                        + meetingRestriction(scope)
                        + " GROUP BY ai.priority";
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        query.setParameter("today", today.toString());
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<OpenByPriority> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new OpenByPriority((String) row[0], count(row[1]), count(row[2])));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public MeetingCoverage meetingCoverage(Scope scope, Window window) {
        // LEFT JOIN plus COUNT(an.id): meeting_analyses is 1-1 with the meeting (UNIQUE meeting_id
        // in V005), so nothing fans out and the second counter is the analysed subset.
        String sql =
                "SELECT COUNT(*), COUNT(an.id) "
                        + "FROM meetings m "
                        + "LEFT JOIN meeting_analyses an ON an.meeting_id = m.id "
                        + "WHERE m.tenant_id = :tenantId "
                        + "  AND m.deleted_at IS NULL "
                        + MEETING_IN_WINDOW
                        + meetingRestriction(scope);
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        bindRange(query, window);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        if (rows.isEmpty()) {
            return new MeetingCoverage(0, 0);
        }
        Object[] row = rows.get(0);
        return new MeetingCoverage(count(row[0]), count(row[1]));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<BucketCount> meetingsPerBucket(Scope scope, Window window) {
        // No join to meeting_analyses: this is every meeting of the bucket, which is what makes
        // the analysed count next to it readable as a coverage figure rather than as a total.
        String bucket = bucketOf(MEETING_INSTANT);
        String sql =
                "SELECT "
                        + bucket
                        + " AS bucket, COUNT(*) "
                        + "FROM meetings m "
                        + "WHERE m.tenant_id = :tenantId "
                        + "  AND m.deleted_at IS NULL "
                        + MEETING_IN_WINDOW
                        + meetingRestriction(scope)
                        + " GROUP BY 1 ORDER BY 1";
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        bindBucketing(query, window);
        bindRange(query, window);
        return toBuckets((List<Object[]>) query.getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<BucketCount> analysedMeetingsPerBucket(Scope scope, Window window) {
        String bucket = bucketOf(MEETING_INSTANT);
        String sql =
                "SELECT "
                        + bucket
                        + " AS bucket, COUNT(*) "
                        + MEETING_SOURCE
                        + "WHERE m.tenant_id = :tenantId "
                        + "  AND m.deleted_at IS NULL "
                        + MEETING_IN_WINDOW
                        + meetingRestriction(scope)
                        + " GROUP BY 1 ORDER BY 1";
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        bindBucketing(query, window);
        bindRange(query, window);
        return toBuckets((List<Object[]>) query.getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<TopicOccurrence> topicOccurrences(Scope scope, Window window) {
        // One row per (meeting, topic). An analysis whose topics array is empty contributes none,
        // which is why a bucket's meeting count is its own query and not a count of these rows.
        String bucket = bucketOf(MEETING_INSTANT);
        String sql =
                "SELECT "
                        + bucket
                        + " AS bucket, m.id, t.topic "
                        + MEETING_SOURCE
                        + "CROSS JOIN LATERAL unnest(an.topics) AS t(topic) "
                        + "WHERE m.tenant_id = :tenantId "
                        + "  AND m.deleted_at IS NULL "
                        + "  AND btrim(t.topic) <> '' "
                        + MEETING_IN_WINDOW
                        + meetingRestriction(scope);
        Query query = em.createNativeQuery(sql);
        bindScope(query, scope);
        bindBucketing(query, window);
        bindRange(query, window);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<TopicOccurrence> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new TopicOccurrence(toLocalDate(row[0]), (UUID) row[1], (String) row[2]));
        }
        return out;
    }

    /* ============================== SQL helpers ============================== */

    /** Local-zone truncation of a {@code TIMESTAMPTZ} column down to the bucket it belongs to. */
    private static String bucketOf(String column) {
        return "CAST(date_trunc(CAST(:unit AS text), "
                + "timezone(CAST(:zone AS text), "
                + column
                + ")) AS date)";
    }

    private static String meetingRestriction(Scope scope) {
        return scope.restricted() ? " AND m.id = ANY(CAST(:meetingIds AS uuid[]))" : "";
    }

    private static void bindScope(Query query, Scope scope) {
        query.setParameter("tenantId", scope.tenantId());
        if (scope.restricted()) {
            query.setParameter("meetingIds", uuidArrayLiteral(scope.meetingIds()));
        }
    }

    /**
     * Range only. Kept apart from {@link #bindBucketing} because JPA throws on a parameter that the
     * query does not contain, and the one query with a range and no buckets is the coverage count.
     */
    private static void bindRange(Query query, Window window) {
        query.setParameter("windowFrom", window.from());
        query.setParameter("windowTo", window.to());
    }

    /** The two binds only the bucketed queries carry. */
    private static void bindBucketing(Query query, Window window) {
        query.setParameter("unit", window.granularity().sqlUnit());
        query.setParameter("zone", window.zone().getId());
    }

    /**
     * Postgres array literal. A UUID's text form has no character an array literal would have to
     * quote, so no escaping is needed and none is invented here.
     */
    private static String uuidArrayLiteral(List<UUID> ids) {
        StringBuilder out = new StringBuilder(ids.size() * 37 + 2).append('{');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(ids.get(i));
        }
        return out.append('}').toString();
    }

    private static List<BucketCount> toBuckets(List<Object[]> rows) {
        List<BucketCount> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new BucketCount(toLocalDate(row[0]), count(row[1])));
        }
        return out;
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate local) {
            return local;
        }
        return ((Date) value).toLocalDate();
    }
}
