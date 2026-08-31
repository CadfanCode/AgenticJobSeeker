package se.caiowain.jobseeker.fit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one full-text query behind the prescreen: which of the candidate's skills does each
 * ad name?
 *
 * <p>Written with {@link JdbcTemplate} rather than a JPA projection so the SQL is readable
 * as SQL — this is the component whose exact wording decides whether the ranking is right.
 *
 * <p>Two choices are load-bearing and must not be "simplified":
 * <ul>
 *   <li>{@code 'simple'} — a generated column needs an IMMUTABLE expression, and stemming
 *       would mangle proper nouns like Spring and React.</li>
 *   <li>{@code phraseto_tsquery} — {@code plainto_tsquery} ANDs the lexemes anywhere in the
 *       document, so "Spring Boot" would match an ad mentioning the two words paragraphs
 *       apart. A phrase query requires adjacency.</li>
 * </ul>
 *
 * <p>A blank skill name yields an empty tsquery, which matches nothing. That is the desired
 * behaviour, so no special case is needed.
 */
@Component
public class SkillMatchQuery {

    private static final String SQL = """
            SELECT p.id AS job_id, s.name AS skill_name
            FROM job_posting p
            JOIN cv_skill s ON s.cv_profile_id = ?
            WHERE p.search_tsv @@ phraseto_tsquery('simple', s.name)
            ORDER BY p.id, s.ordinal
            """;

    private final JdbcTemplate jdbc;

    public SkillMatchQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Job posting id → the skill names that posting mentions, in profile order. */
    public Map<Long, List<String>> matchesByJob(Long profileId) {
        Map<Long, List<String>> byJob = new LinkedHashMap<>();
        jdbc.query(SQL, rs -> {
            byJob.computeIfAbsent(rs.getLong("job_id"), key -> new ArrayList<>())
                    .add(rs.getString("skill_name"));
        }, profileId);
        return byJob;
    }
}
