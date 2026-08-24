package de.tasticgames.lobby.config;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A bundled {@code config/<name>.yml} that is not listed in {@link LobbyConfigurationService#FILES}
 * is never loaded, and the first {@code raw("<name>")} kills the whole startup. This test is what
 * keeps both sides together.
 */
class LobbyConfigurationFilesTest {

    @Test
    void everyBundledConfigFileIsLoaded() throws Exception {
        URL directory = getClass().getClassLoader().getResource("config");
        assertNotNull(directory, "config/ is not on the test classpath");

        List<String> bundled;
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            bundled = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - ".yml".length()))
                    .sorted()
                    .toList();
        }

        assertEquals(bundled, LobbyConfigurationService.FILES.stream().sorted().toList(),
                "every bundled config/<name>.yml must appear in LobbyConfigurationService.FILES");
    }
}
