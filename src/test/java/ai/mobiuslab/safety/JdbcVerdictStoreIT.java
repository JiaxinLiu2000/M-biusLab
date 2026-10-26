package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.safety.SafetyVerdict.Decision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Idempotency of verdict persistence against the local MySQL (docker compose). */
@Tag("integration")
class JdbcVerdictStoreIT {

    static JdbcClient jdbc;

    @BeforeAll
    static void schema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:mysql://localhost:3307/mobiuslab?allowPublicKeyRetrieval=true&useSSL=false", "mobiuslab", "mobiuslab");
        jdbc = JdbcClient.create(ds);
        for (String file : List.of("V1__common.sql", "V5__safety.sql")) {
            try (InputStream in = JdbcVerdictStoreIT.class.getResourceAsStream("/db/migration/" + file)) {
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
                for (String stmt : sql.split(";")) {
                    if (!stmt.isBlank()) {
                        jdbc.sql(stmt.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")).update();
                    }
                }
            }
        }
    }

    @Test
    void repeatedProposalIdReturnsStoredVerdict() {
        JdbcVerdictStore store = new JdbcVerdictStore(jdbc, new RevisionLog(jdbc));
        ActionProposal p = new ActionProposal(Ids.newId("prp"), Actor.agent("a1"), ActionKind.FILE_WRITE, "w/x", "d", "c", null);
        SafetyVerdict first = new SafetyVerdict(p.id(), Decision.ALLOW, 0.1, List.of(), List.of("ok"), List.of("hard-rules"));
        SafetyVerdict second = new SafetyVerdict(p.id(), Decision.DENY, 0.9, List.of("x"), List.of("no"), List.of("llm-review"));
        assertThat(store.saveIfAbsent(p, first)).isEqualTo(first);
        assertThat(new JdbcVerdictStore(jdbc, new RevisionLog(jdbc)).saveIfAbsent(p, second)).isEqualTo(first);
        assertThat(new JdbcVerdictStore(jdbc, new RevisionLog(jdbc)).find(p.id())).contains(first);
        assertThat(store.page(null, 5)).isNotEmpty();
    }
}
