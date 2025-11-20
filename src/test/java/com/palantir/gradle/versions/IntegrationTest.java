/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.versions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import nebula.test.dependencies.DependencyGraph;
import nebula.test.dependencies.GradleDependencyGenerator;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base class for integration tests providing common utility methods.
 * This class serves as a replacement for the Groovy IntegrationSpec class.
 */
public class IntegrationTest {

    @TempDir
    protected Path projectDir;

    @BeforeEach
    void setup() throws IOException {
        // Create settings.gradle
        File settingsFile = projectDir.resolve("settings.gradle").toFile();
        Files.writeString(settingsFile.toPath(), "");
    }

    /**
     * Generates a test Maven repository from the given dependency graph strings.
     *
     * @param graph dependency graph specifications
     * @return the generated Maven repository directory
     */
    protected File generateMavenRepo(String... graph) {
        DependencyGraph dependencyGraph = new DependencyGraph(graph);
        GradleDependencyGenerator generator = new GradleDependencyGenerator(
                dependencyGraph, new File(projectDir.toFile(), "build/testrepogen").toString());
        return generator.generateTestMavenRepo();
    }

    /**
     * Runs the specified tasks twice with configuration cache and verifies cache behavior.
     * Returns true if the configuration cache was properly used on the second run.
     *
     * @param tasks the tasks to run
     * @return true if configuration cache was properly used
     */
    protected boolean runTasksWithConfigurationCache(String... tasks) {
        // Prepare arguments for first run
        List<String> argsFirstRun = new ArrayList<>(Arrays.asList(tasks));
        argsFirstRun.add("--configuration-cache");

        // First run - should store configuration cache
        BuildResult firstRun = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(argsFirstRun)
                .withPluginClasspath()
                .build();

        assertThat(firstRun.getOutput())
                .as("Expected first run to store configuration cache, but output was: %s", firstRun.getOutput())
                .contains("Configuration cache entry stored.");

        // Prepare arguments for second run
        List<String> argsSecondRun = new ArrayList<>(Arrays.asList(tasks));
        argsSecondRun.add("--configuration-cache");

        // Second run - should reuse configuration cache
        BuildResult secondRun = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(argsSecondRun)
                .withPluginClasspath()
                .build();

        assertThat(secondRun.getOutput())
                .as("Expected second run to reuse configuration cache, but output was: %s", secondRun.getOutput())
                .contains("Configuration cache entry reused.");

        // Clean up configuration cache directory
        Path configCacheDir = projectDir.resolve(".gradle/configuration-cache");
        if (Files.exists(configCacheDir)) {
            deleteRecursively(configCacheDir);
        }
        assertThat(configCacheDir)
                .as("Configuration cache directory was not deleted")
                .doesNotExist();

        return true;
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param path the path to delete
     */
    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.list(path)) {
                    stream.forEach(IntegrationTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete: " + path, e);
        }
    }
}
