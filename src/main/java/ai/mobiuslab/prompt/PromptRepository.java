package ai.mobiuslab.prompt;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written SQL for {@code prompt_template}; all reads use the (prompt_key, version) unique index. */
@Repository
public class PromptRepository {

    private static final TypeReference<List<PromptSegment>> SEGMENTS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String COLUMNS = "prompt_key, version, parent_version, segments_json, variables_json, "
            + "owner_agent_id, invocations, created_by, created_at, row_version";

    /** A row plus its optimistic-lock version. */
    public record Row(PromptTemplate template, int rowVersion) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(new PromptTemplate(
            rs.getString(1), rs.getInt(2), (Integer) rs.getObject(3), Json.read(rs.getString(4), SEGMENTS),
            Json.read(rs.getString(5), STRINGS), rs.getString(6), rs.getLong(7), rs.getString(8),
            rs.getTimestamp(9).toInstant()), rs.getInt(10));

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public PromptRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** One version (not deleted). */
    public Optional<Row> find(String key, int version) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM prompt_template WHERE prompt_key = :k AND version = :v AND deleted = 0")
                .param("k", key).param("v", version).query(MAPPER).optional();
    }

    /** Highest version (not deleted). */
    public Optional<Row> latest(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM prompt_template WHERE prompt_key = :k AND deleted = 0 "
                        + "ORDER BY version DESC LIMIT 1")
                .param("k", key).query(MAPPER).optional();
    }

    /** All versions, newest first. */
    public List<PromptTemplate> history(String key) {
        List<PromptTemplate> out = new ArrayList<>();
        jdbc.sql("SELECT " + COLUMNS + " FROM prompt_template WHERE prompt_key = :k AND deleted = 0 ORDER BY version DESC")
                .param("k", key).query(MAPPER).list().forEach(r -> out.add(r.template()));
        return out;
    }

    /** True when any version of the key exists. */
    public boolean exists(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM prompt_template WHERE prompt_key = :k")
                .param("k", key).query(Long.class).single() > 0;
    }

    /** Inserts an ACTIVE version; duplicate (key, version) raises DuplicateKeyException. */
    public void insert(PromptTemplate t, Actor actor) {
        jdbc.sql("""
                        INSERT INTO prompt_template (prompt_key, version, parent_version, segments_json, variables_json,
                          owner_agent_id, invocations, status, created_by, updated_by)
                        VALUES (:k, :v, :p, :s, :vars, :o, 0, 'ACTIVE', :a, :a)""")
                .param("k", t.key()).param("v", t.version()).param("p", t.parentVersion())
                .param("s", Json.write(t.segments())).param("vars", Json.write(t.variables()))
                .param("o", t.ownerAgentId()).param("a", actor.asString())
                .update();
    }

    /** Marks a version SUPERSEDED if its row_version still matches; returns false on a concurrent change. */
    public boolean supersede(String key, int version, int rowVersion, Actor actor) {
        return jdbc.sql("""
                        UPDATE prompt_template SET status = 'SUPERSEDED', row_version = row_version + 1, updated_by = :a
                        WHERE prompt_key = :k AND version = :v AND row_version = :rv AND deleted = 0""")
                .param("a", actor.asString()).param("k", key).param("v", version).param("rv", rowVersion)
                .update() == 1;
    }

    /** Adds invocation deltas in one batch round trip (metric counter, not an audited content change). */
    public void addInvocations(Map<InvocationCounter.Ref, Long> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(deltas.size());
        deltas.forEach((ref, n) -> args.add(new Object[]{n, ref.key(), ref.version()}));
        int[] counts = template.batchUpdate(
                "UPDATE prompt_template SET invocations = invocations + ? WHERE prompt_key = ? AND version = ?", args);
        if (counts.length != args.size()) {
            throw new MobiusException(ErrorCode.INTERNAL, "invocation flush updated " + counts.length + " of " + args.size());
        }
    }
}
