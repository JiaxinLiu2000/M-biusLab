package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.memory.index.QdrantIndex;
import ai.mobiuslab.memory.store.JdbcMemoryNoteStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.mobiuslab.memory.MemoryTestSupport.note;
import static org.assertj.core.api.Assertions.assertThat;

/** Write → Qdrant search with scope filtering against the local MySQL (3307) and Qdrant (6333). */
@Tag("integration")
class MemoryQdrantIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void writeThenSearchViaQdrantWithScopeFilter() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:mysql://localhost:3307/mobiuslab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "mobiuslab", "mobiuslab");
        JdbcClient jdbc = JdbcClient.create(ds);
        boolean created = jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'memory_note'")
                .query(Integer.class).single() == 0;
        if (created) {
            String ddl = new String(getClass().getResourceAsStream("/db/migration/V10__memory.sql").readAllBytes(), StandardCharsets.UTF_8);
            jdbc.sql(ddl.substring(ddl.indexOf("CREATE TABLE"))).update();
        }
        String collection = "mobius_memory_it_" + System.nanoTime();
        QdrantIndex index = new QdrantIndex("http://localhost:6333", collection, Duration.ofSeconds(10), new HashingEmbedder());
        try {
            assertThat(index.ensureCollection()).isTrue();
            MemoryTestSupport t = new MemoryTestSupport(dir, Map.of(
                    "alice", Set.of("PRIVATE", "PUBLIC", "GROUP:g1"),
                    "bob", Set.of("PRIVATE", "PUBLIC")),
                    new JdbcMemoryNoteStore(jdbc, new RevisionLog(jdbc)), index);
            String tag = "zeolite" + System.nanoTime();
            MemoryNote pub = t.service.write(Actor.agent("alice"), "alice", note(MemoryScope.PUBLIC, null, "Public " + tag, tag + " adsorption result"));
            MemoryNote grp = t.service.write(Actor.agent("alice"), "alice", note(MemoryScope.GROUP, "g1", "Group " + tag, tag + " adsorption group result"));
            assertThat(t.store.find(pub.id()).orElseThrow().indexPending()).isFalse();
            assertThat(index.ready()).isTrue();

            MemoryQuery q = new MemoryQuery(tag + " adsorption", null, List.of("g1"), null, 10, false);
            assertThat(t.service.search("alice", q)).extracting(h -> h.note().id()).contains(pub.id(), grp.id());
            assertThat(t.service.search("bob", q)).extracting(h -> h.note().id()).contains(pub.id()).doesNotContain(grp.id());

            t.service.invalidate(Actor.agent("alice"), "alice", pub.id(), "it", null);
            assertThat(t.service.search("bob", q)).extracting(h -> h.note().id()).doesNotContain(pub.id());
            assertThat(index.ready()).isTrue();
        } finally {
            java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://localhost:6333/collections/" + collection)).DELETE().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            if (created) {
                jdbc.sql("DROP TABLE memory_note").update();
            }
        }
    }
}
