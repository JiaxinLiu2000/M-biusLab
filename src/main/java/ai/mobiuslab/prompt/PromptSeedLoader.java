package ai.mobiuslab.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Imports every {@code classpath*:prompts/*.yml} prompt as version 1 on startup (idempotent). */
@Component
public class PromptSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptSeedLoader.class);

    /** One seed file: a list of prompts. */
    record SeedFile(List<Seed> prompts) {
    }

    /** One seed prompt. */
    record Seed(String key, String ownerAgentId, List<PromptSegment> segments) {
    }

    private final DbPromptLibrary library;

    public PromptSeedLoader(DbPromptLibrary library) {
        this.library = library;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        YAMLMapper yaml = YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:prompts/*.yml");
        int imported = 0;
        int total = 0;
        for (Resource f : files) {
            SeedFile file;
            try (InputStream in = f.getInputStream()) {
                file = yaml.readValue(in, SeedFile.class);
            } catch (IOException e) {
                log.error("cannot parse prompt seed file {}: {}", f.getFilename(), e.getMessage());
                continue;
            }
            if (file == null || file.prompts() == null) {
                continue;
            }
            for (Seed s : file.prompts()) {
                total++;
                if (s.key() == null || s.segments() == null || s.segments().isEmpty()) {
                    log.error("invalid prompt seed in {}: key and segments are required", f.getFilename());
                    continue;
                }
                if (library.importSeed(s.key(), s.segments(), s.ownerAgentId())) {
                    imported++;
                }
            }
        }
        log.info("prompt seeds: {} files, {} prompts, {} newly imported", files.length, total, imported);
    }
}
