package com.example.orchestrator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the template library: every file is indexed, every index entry exists, placeholders are known. */
class TemplateIntegrityTest {

    private static final Set<String> KNOWN = Set.of("ALLOWED_SCHEMES", "HTTP_CHECK", "DENYLIST", "CACHE_TTL", "SERVICE_VERSION");

    private static Path templates() throws URISyntaxException {
        return Paths.get(TemplateIntegrityTest.class.getResource("/templates").toURI());
    }

    @Test
    void everyTemplateFileIsListedInItsChangesetIndexAndViceVersa() throws Exception {
        Path root = templates();
        List<Path> changesets;
        try (Stream<Path> s = Files.walk(root)) {
            changesets = s.filter(p -> p.getFileName().toString().equals("index.txt")).map(Path::getParent).toList();
        }
        assertThat(changesets).hasSize(13);
        for (Path cs : changesets) {
            Set<String> listed = Files.readAllLines(cs.resolve("index.txt")).stream().map(String::trim)
                    .filter(l -> !l.isEmpty()).collect(Collectors.toSet());
            Set<String> onDisk;
            try (Stream<Path> s = Files.walk(cs)) {
                onDisk = s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("index.txt"))
                        .map(p -> cs.relativize(p).toString().replace('\\', '/')).collect(Collectors.toSet());
            }
            assertThat(listed).as(root.relativize(cs).toString()).isEqualTo(onDisk);
        }
    }

    @Test
    void onlyKnownPlaceholdersAreUsed() throws IOException, URISyntaxException {
        Pattern p = Pattern.compile("@@([A-Z_]+)@@");
        try (Stream<Path> s = Files.walk(templates())) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) assertThat(KNOWN).as(f + " uses " + m.group(1)).contains(m.group(1));
            }
        }
    }
}
