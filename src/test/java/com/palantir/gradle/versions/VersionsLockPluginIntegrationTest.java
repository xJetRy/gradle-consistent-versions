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

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.files.gradle.GradleFile;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class VersionsLockPluginIntegrationTest {

    // ***DELINEATOR FOR REVIEW: PLUGIN_NAME
    private static final String PLUGIN_NAME = "com.palantir.versions-lock";

    // ***DELINEATOR FOR REVIEW: setup
    @BeforeEach
    void setup(MavenRepo mavenRepo, RootProject rootProject) {
        // Publish test artifacts to maven repo
        mavenRepo.publish(
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.11"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.20"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.24"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.25"),
                MavenArtifact.builder()
                        .coordinate("ch.qos.logback:logback-classic:1.2.3")
                        .addDependency("org.slf4j:slf4j-api:1.7.25")
                        .build(),
                MavenArtifact.of("junit:junit:4.10"),
                MavenArtifact.builder()
                        .coordinate("org:test-dep-that-logs:1.0")
                        .addDependency("org.slf4j:slf4j-api:1.7.11")
                        .build(),
                MavenArtifact.of("org:another-transitive-dependency:3.2.1"),
                MavenArtifact.builder()
                        .coordinate("org:another-direct-dependency:1.2.3")
                        .addDependency("org:another-transitive-dependency:3.2.1")
                        .build());

        // Create platform POM
        makePlatformPom(mavenRepo, "org", "platform", "1.0");

        setupBuildFile(rootProject, mavenRepo);
    }

    private GradleFile setupBuildFile(RootProject rootProject, MavenRepo mavenRepo) {
        rootProject.buildGradle().append("""
                buildscript {
                    repositories {
                        mavenCentral()
                    }
                }
                """);

        rootProject.buildGradle().plugins().add(PLUGIN_NAME);

        rootProject.buildGradle().withMavenRepo(mavenRepo);

        rootProject.buildGradle().append("""
                allprojects {
                    task resolveConfigurations {
                        doLast {
                            if (pluginManager.hasPlugin('java')) {
                                configurations.compileClasspath.resolve()
                                configurations.runtimeClasspath.resolve()
                            }
                        }
                    }
                }
                """);

        return rootProject.buildGradle();
    }

    // ***DELINEATOR FOR REVIEW: can_write_locks
    @Test
    void can_write_locks(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().exists();
    }

    // ***DELINEATOR FOR REVIEW: standardSetup
    private void standardSetup(SubProject foo, SubProject bar, SubProject forced, RootProject rootProject) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
                dependencies {
                    implementation 'org.slf4j:slf4j-api:1.7.24'
                }
                """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().append("""
                dependencies {
                    implementation "org.slf4j:slf4j-api:${project.bar_version}"
                }
                """);

        rootProject.gradlePropertiesFile().appendProperty("bar_version", "1.7.11");

        forced.buildGradle().plugins().add("java");
        forced.buildGradle().append("""
                dependencies {
                    implementation "org.slf4j:slf4j-api"
                }
                configurations.all {
                    resolutionStrategy {
                        force "org.slf4j:slf4j-api:1.7.20"
                    }
                }
                """);
    }

    // ***DELINEATOR FOR REVIEW: cannot_resolve_without_a_root_lock_file
    @Test
    void cannot_resolve_without_a_root_lock_file(
            GradleInvoker gradle, SubProject foo, SubProject bar, SubProject forced, RootProject rootProject) {
        standardSetup(foo, bar, forced, rootProject);

        InvocationResult result = gradle.withArgs("resolveConfigurations").buildsWithFailure();

        assertThat(result).output().containsPattern(".*Root lock file '([^']+)' doesn't exist, please run.*");
    }

    // ***DELINEATOR FOR REVIEW: can_resolve_without_a_root_lock_file_if_lock_file_is_ignored
    @Test
    void can_resolve_without_a_root_lock_file_if_lock_file_is_ignored(
            GradleInvoker gradle, SubProject foo, SubProject bar, SubProject forced, RootProject rootProject) {
        standardSetup(foo, bar, forced, rootProject);

        gradle.withArgs("resolveConfigurations", "-PignoreLockFile").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: consolidates_subproject_dependencies
    @Test
    void consolidates_subproject_dependencies(
            GradleInvoker gradle, SubProject foo, SubProject bar, SubProject forced, RootProject rootProject) {
        String expectedError = "Locked by versions.lock";
        standardSetup(foo, bar, forced, rootProject);

        rootProject.buildGradle().append("""
                subprojects {
                    configurations.matching { it.name == 'runtimeClasspath' }.all {
                        resolutionStrategy.activateDependencyLocking()
                    }
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("resolveConfigurations", "--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(rootProject.file("versions.lock").text())
                .contains("org.slf4j:slf4j-api:1.7.24");

        verifyLockfile(foo.path(), "org.slf4j:slf4j-api:1.7.24");
        verifyLockfile(bar.path(), "org.slf4j:slf4j-api:1.7.24");

        // ***DELINEATOR FOR REVIEW: then
        verifyLockfile(forced.path(), "org.slf4j:slf4j-api:1.7.20");

        // ***DELINEATOR FOR REVIEW: then
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult incompatible =
                gradle.withArgs("-Pbar_version=1.7.25", "resolveConfigurations").buildsWithFailure();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(incompatible).output().contains(expectedError);
    }

    // ***DELINEATOR FOR REVIEW: works_on_just_root_project
    @Test
    void works_on_just_root_project(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String versionsLock = rootProject.file("versions.lock").text();
        assertThat(versionsLock).contains("ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)");
        assertThat(versionsLock).contains("org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)");
    }

    // ***DELINEATOR FOR REVIEW: get_a_conflict_even_if_no_lock_files_applied
    @Test
    void get_a_conflict_even_if_no_lock_files_applied(
            GradleInvoker gradle, SubProject foo, SubProject bar, SubProject forced, RootProject rootProject) {
        String expectedError = "Locked by versions.lock";
        standardSetup(foo, bar, forced, rootProject);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(rootProject.file("versions.lock").text())
                .contains("org.slf4j:slf4j-api:1.7.24");

        // ***DELINEATOR FOR REVIEW: then
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult incompatible =
                gradle.withArgs("-Pbar_version=1.7.25", "resolveConfigurations").buildsWithFailure();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(incompatible).output().contains(expectedError);
    }

    // ***DELINEATOR FOR REVIEW: fails_fast_when_subproject_that_is_depended_on_has_same_name_as_root_project
    @Test
    void fails_fast_when_subproject_that_is_depended_on_has_same_name_as_root_project(
            GradleInvoker gradle, SubProject foobar, SubProject other, RootProject rootProject) {
        String expectedError = "This plugin doesn't work if the root project shares both group and name with a subproject";

        rootProject.buildGradle().append("""
                allprojects {
                    group 'same'
                }
                """);

        rootProject.settingsGradle().rootProjectName("foobar");

        foobar.buildGradle().plugins().add("java-library");

        other.buildGradle().plugins().add("java-library");
        other.buildGradle().append("""
                dependencies {
                    implementation project(':foobar')
                }
                """);

        // Otherwise the lack of a lock file will throw first
        rootProject.file("versions.lock").overwrite("");

        InvocationResult error = gradle.withArgs().buildsWithFailure();

        assertThat(error).output().contains(expectedError);
    }

    // ***DELINEATOR FOR REVIEW: fails_fast_when_multiple_subprojects_share_the_same_coordinate
    @Test
    void fails_fast_when_multiple_subprojects_share_the_same_coordinate(
            GradleInvoker gradle, RootProject rootProject) {
        String expectedError = "All subprojects must have unique $group:$name";

        rootProject.buildGradle().append("""
                allprojects {
                    group 'same'
                }
                """);

        // both projects will have name = 'a'
        rootProject.subproject("foo").subproject("a");
        rootProject.subproject("bar").subproject("a");

        // Otherwise the lack of a lock file will throw first
        rootProject.file("versions.lock").overwrite("");

        InvocationResult error = gradle.withArgs().buildsWithFailure();

        assertThat(error).output().contains(expectedError);
    }

    // ***DELINEATOR FOR REVIEW: detects_failOnVersionConflict_on_locked_configuration
    @Test
    void detects_failOnVersionConflict_on_locked_configuration(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                configurations.compileClasspath.resolutionStrategy.failOnVersionConflict()
                """);

        rootProject.file("versions.lock").overwrite("");

        InvocationResult failure = gradle.withArgs().buildsWithFailure();

        assertThat(failure).output().contains("Must not use failOnVersionConflict");
    }

    // ***DELINEATOR FOR REVIEW: ignores_failOnVersionConflict_on_non_locked_configuration
    @Test
    void ignores_failOnVersionConflict_on_non_locked_configuration(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                configurations {
                    foo {
                        resolutionStrategy.failOnVersionConflict()
                    }
                }
                """);

        rootProject.file("versions.lock").overwrite("");

        gradle.withArgs().buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: fails_if_new_dependency_added_that_was_not_in_the_lock_file
    @Test
    void fails_if_new_dependency_added_that_was_not_in_the_lock_file(
            GradleInvoker gradle, SubProject foo, MavenRepo mavenRepo) {
        String expectedError = "Found dependencies that were not in the lock state";

        mavenRepo.publish(MavenArtifact.of("org:a:1.0"), MavenArtifact.of("org:b:1.0"));

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
                dependencies {
                    implementation 'org:a:1.0'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: when
        foo.buildGradle().append("""
                dependencies {
                    implementation 'org:b:1.0'
                }
                """);

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult failure = gradle.withArgs(":check").buildsWithFailure();

        assertThat(failure).task(":verifyLocks").outcome().isEqualTo(TaskOutcome.FAILED);
        assertThat(failure).output().contains(expectedError);

        // ***DELINEATOR FOR REVIEW: and
        gradle.withArgs("--write-locks").buildsSuccessfully();
        gradle.withArgs("verifyLocks").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: does_not_fail_if_unifiedClasspath_is_unresolvable
    @Test
    void does_not_fail_if_unifiedClasspath_is_unresolvable(
            GradleInvoker gradle, SubProject foo, RootProject rootProject) {
        rootProject.file("versions.lock").overwrite("""
                org.slf4j:slf4j-api:1.7.11 (0 constraints: 0000000)
                """);

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
                dependencies {
                    implementation 'org.slf4j:slf4j-api:1.7.20'
                }
                """);

        gradle.withArgs("dependencies", "--configuration", "unifiedClasspath").buildsSuccessfully();
        gradle.withArgs().buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: fails_if_dependency_was_removed_but_still_in_the_lock_file
    @Test
    void fails_if_dependency_was_removed_but_still_in_the_lock_file(
            GradleInvoker gradle, SubProject foo, MavenRepo mavenRepo) {
        String expectedError = "Locked dependencies missing from the resolution result";

        mavenRepo.publish(MavenArtifact.of("org:a:1.0"), MavenArtifact.of("org:b:1.0"));

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
                dependencies {
                    implementation 'org:a:1.0'
                    implementation 'org:b:1.0'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: when
        foo.buildGradle().overwrite("""
                dependencies {
                    implementation 'org:a:1.0'
                }
                """);

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult failure = gradle.withArgs(":check").buildsWithFailure();

        assertThat(failure).task(":verifyLocks").outcome().isEqualTo(TaskOutcome.FAILED);
        assertThat(failure).output().contains(expectedError);

        // ***DELINEATOR FOR REVIEW: and
        gradle.withArgs("--write-locks").buildsSuccessfully();
        gradle.withArgs("verifyLocks").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: why_works
    @Test
    void why_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult result = gradle.withArgs("why", "--dependency", "slf4j-api").buildsSuccessfully();

        assertThat(result).output().contains("org.slf4j:slf4j-api:1.7.25");
        assertThat(result).output().contains("ch.qos.logback:logback-classic -> 1.7.25");
    }

    // ***DELINEATOR FOR REVIEW: why_with_hash_works
    @Test
    void why_with_hash_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult result = gradle.withArgs("why", "--hash", "400d4d2a").buildsSuccessfully(); // slf4j-api

        assertThat(result).output().contains("org.slf4j:slf4j-api:1.7.25");
        assertThat(result).output().contains("ch.qos.logback:logback-classic -> 1.7.25");
    }

    // ***DELINEATOR FOR REVIEW: why_with_comma_delimited_multiple_hashes_works
    @Test
    void why_with_comma_delimited_multiple_hashes_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
                    implementation 'org:another-direct-dependency:1.2.3' // brings in org:another-transitive-dependency:3.2.1
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult result =
                gradle.withArgs("why", "--hash", "400d4d2a,050d6518").buildsSuccessfully(); // both transitive dependencies

        assertThat(result).output().contains("org.slf4j:slf4j-api:1.7.25");
        assertThat(result).output().contains("ch.qos.logback:logback-classic -> 1.7.25");
        assertThat(result).output().contains("org:another-transitive-dependency:3.2.1");
        assertThat(result).output().contains("org:another-direct-dependency -> 3.2.1");
    }

    // ***DELINEATOR FOR REVIEW: does_not_fail_if_subproject_evaluated_later_applies_base_plugin_in_own_build_file
    @Test
    void does_not_fail_if_subproject_evaluated_later_applies_base_plugin_in_own_build_file(
            GradleInvoker gradle, RootProject rootProject) {
        SubProject foo = rootProject.subproject("foo");
        foo.buildGradle().plugins().add("java-library");
        foo.buildGradle().append("""
                dependencies {
                    implementation project(':foo:bar')
                }
                """);

        // Need to make sure bar is evaluated after foo, so we're nesting it!
        SubProject bar = foo.subproject("bar");
        bar.buildGradle().plugins().add("java-library");

        gradle.withArgs("--write-locks").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: locks_platform
    @Test
    void locks_platform(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation platform('org:platform:1.0')
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        String versionsLock = rootProject.file("versions.lock").text();
        assertThat(versionsLock).isEqualTo("""
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

                org:platform:1.0 (1 constraints: a5041a2c)
                """);
    }

    // ***DELINEATOR FOR REVIEW: verifyLocks_is_cacheable
    @Test
    void verifyLocks_is_cacheable(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation "org.slf4j:slf4j-api:$depVersion"
                }
                """);

        rootProject.gradlePropertiesFile().appendProperty("depVersion", "1.7.20");

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult firstRun = gradle.withArgs("verifyLocks").buildsSuccessfully();
        assertThat(firstRun).task(":verifyLocks").outcome().isEqualTo(TaskOutcome.SUCCESS);

        InvocationResult secondRun = gradle.withArgs("verifyLocks").buildsSuccessfully();
        assertThat(secondRun).task(":verifyLocks").outcome().isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    // ***DELINEATOR FOR REVIEW: verifyLocks_current_lock_state_does_not_get_poisoned_by_existing_lock_file
    @Test
    void verifyLocks_current_lock_state_does_not_get_poisoned_by_existing_lock_file(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation "org.slf4j:slf4j-api:$depVersion"
                }
                """);

        rootProject.gradlePropertiesFile().appendProperty("depVersion", "1.7.20");

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        InvocationResult fail = gradle.withArgs("verifyLocks", "-PdepVersion=1.7.11").buildsWithFailure();

        // ***DELINEATOR FOR REVIEW: and
        assertThat(fail).output().contains("""
                > Found dependencies whose dependents changed:
                  -org.slf4j:slf4j-api:1.7.20 (1 constraints: 3c05433b)
                  +org.slf4j:slf4j-api:1.7.11 (1 constraints: 3c05423b)
                """);
    }

    // ***DELINEATOR FOR REVIEW: excludes_from_compileOnly_do_not_obscure_real_dependency
    @Test
    void excludes_from_compileOnly_do_not_obscure_real_dependency(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                }
                configurations.compileOnly {
                    // convoluted, but the idea is to exclude a transitive
                    exclude group: 'org.slf4j', module: 'slf4j-api'
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        String versionsLock = rootProject.file("versions.lock").text();
        assertThat(versionsLock).isEqualTo("""
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

                ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

                org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)
                """);
    }

    // ***DELINEATOR FOR REVIEW: can_resolve_configuration_dependency
    @Test
    void can_resolve_configuration_dependency(GradleInvoker gradle, SubProject foo, SubProject bar, RootProject rootProject) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
                dependencies {
                    implementation project(path: ":bar", configuration: "fun")
                }
                """);

        bar.buildGradle().append("""
                configurations {
                    fun
                }

                dependencies {
                    fun 'ch.qos.logback:logback-classic:1.2.3'
                }
                """);

        // Make sure that we can still add dependencies to the original 'fun' configuration after resolving lock state.
        //
        // Adding a constraint to 'fun' calls Configuration.preventIllegalMutation() which fails if observedState is
        // GRAPH_RESOLVED or ARTIFACTS_RESOLVED. That would happen if a configuration that extends from it has been
        // resolved.
        rootProject.buildGradle().append("""
                configurations.unifiedClasspath.incoming.afterResolve {
                    project(':bar').dependencies.constraints {
                        fun 'some:other-dep'
                    }
                }
                """);

        gradle.withArgs("--write-locks", "classes").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: inter_project_normal_dependency_works
    @Test
    void inter_project_normal_dependency_works(GradleInvoker gradle, SubProject foo, SubProject bar) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
                dependencies {
                    implementation project(":bar")
                }
                """);

        bar.buildGradle().plugins().add("java");

        gradle.withArgs("--write-locks", "classes").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: test_dependencies_appear_in_a_separate_block
    @Test
    void test_dependencies_appear_in_a_separate_block(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                    testImplementation 'org:test-dep-that-logs:1.0'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

                ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

                org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)



                [Test dependencies]

                org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
                """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    // ***DELINEATOR FOR REVIEW: locks_dependencies_from_extra_source_sets_that_end_in_test
    @Test
    void locks_dependencies_from_extra_source_sets_that_end_in_test(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                sourceSets {
                    eteTest
                }
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                    testImplementation 'junit:junit:4.10'
                    eteTestImplementation 'org:test-dep-that-logs:1.0'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

                ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

                org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)



                [Test dependencies]

                junit:junit:4.10 (1 constraints: d904fd30)

                org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
                """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    // ***DELINEATOR FOR REVIEW: versionsLock_testProject_works
    @Test
    void versionsLock_testProject_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'junit:junit:4.10'
                }

                versionsLock.testProject()
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.



                [Test dependencies]

                junit:junit:4.10 (1 constraints: d904fd30)
                """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    // ***DELINEATOR FOR REVIEW: constraints_on_production_do_not_affect_scope_of_test_only_dependencies
    @Test
    void constraints_on_production_do_not_affect_scope_of_test_only_dependencies(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    constraints {
                        implementation 'ch.qos.logback:logback-classic:1.2.3'
                    }
                    dependencies {
                        testImplementation 'ch.qos.logback:logback-classic'
                    }
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.



                [Test dependencies]

                ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

                org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)
                """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    // ***DELINEATOR FOR REVIEW: published_constraints_are_derived_from_lock_file_with_local_constraints
    @Test
    void published_constraints_are_derived_from_lock_file_with_local_constraints(
            GradleInvoker gradle, SubProject foo, SubProject bar, RootProject rootProject) throws IOException {
        // Test with local constraints enabled
        rootProject.gradlePropertiesFile()
                .appendProperty("com.palantir.gradle.versions.publishLocalConstraints", "true");

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().plugins().add("maven-publish");
        foo.buildGradle().append("""
                group = 'com.palantir.published-constraints'
                version = '1.2.3'
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                }
                """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().plugins().add("maven-publish");
        bar.buildGradle().append("""
                group = 'com.palantir.published-constraints'
                version = '1.2.3'
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
                dependencies {
                    implementation 'junit:junit:4.10'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
                .buildsSuccessfully();

        MetadataFile.Dependency junitDep = createDependency("junit", "junit", "4.10");
        MetadataFile.Dependency logbackDep = createDependency("ch.qos.logback", "logback-classic", "1.2.3");
        MetadataFile.Dependency slf4jDep = createDependency("org.slf4j", "slf4j-api", "1.7.25");
        MetadataFile.Dependency fooDep =
                createDependency("com.palantir.published-constraints", "foo", "1.2.3");
        MetadataFile.Dependency barDep =
                createDependency("com.palantir.published-constraints", "bar", "1.2.3");

        // ***DELINEATOR FOR REVIEW: then
        File fooMetadataFilename = foo.buildDir()
                .file("publications/maven/module.json")
                .path()
                .toFile();
        MetadataFile fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile.class);

        Set<MetadataFile.Variant> expectedFooVariants = Set.of(
                createVariant(
                        "runtimeElements",
                        Set.of(logbackDep),
                        Set.of(barDep, junitDep, logbackDep, slf4jDep)),
                createVariant("apiElements", null, Set.of(barDep, junitDep, logbackDep, slf4jDep)));

        assertThat(fooMetadata.variants).isEqualTo(expectedFooVariants);

        // ***DELINEATOR FOR REVIEW: and
        File barMetadataFilename = bar.buildDir()
                .file("publications/maven/module.json")
                .path()
                .toFile();
        MetadataFile barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile.class);

        Set<MetadataFile.Variant> expectedBarVariants = Set.of(
                createVariant(
                        "runtimeElements",
                        Set.of(junitDep),
                        Set.of(fooDep, junitDep, logbackDep, slf4jDep)),
                createVariant("apiElements", null, Set.of(fooDep, junitDep, logbackDep, slf4jDep)));

        assertThat(barMetadata.variants).isEqualTo(expectedBarVariants);
    }

    // ***DELINEATOR FOR REVIEW: published_constraints_are_derived_from_lock_file_without_local_constraints
    @Test
    void published_constraints_are_derived_from_lock_file_without_local_constraints(
            GradleInvoker gradle, SubProject foo, SubProject bar) throws IOException {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().plugins().add("maven-publish");
        foo.buildGradle().append("""
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                }
                """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().plugins().add("maven-publish");
        bar.buildGradle().append("""
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
                dependencies {
                    implementation 'junit:junit:4.10'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
                .buildsSuccessfully();

        MetadataFile.Dependency junitDep = createDependency("junit", "junit", "4.10");
        MetadataFile.Dependency logbackDep = createDependency("ch.qos.logback", "logback-classic", "1.2.3");
        MetadataFile.Dependency slf4jDep = createDependency("org.slf4j", "slf4j-api", "1.7.25");

        // ***DELINEATOR FOR REVIEW: then
        File fooMetadataFilename = foo.buildDir()
                .file("publications/maven/module.json")
                .path()
                .toFile();
        MetadataFile fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile.class);

        Set<MetadataFile.Variant> expectedFooVariants = Set.of(
                createVariant("apiElements", null, Set.of(junitDep, logbackDep, slf4jDep)),
                createVariant(
                        "runtimeElements",
                        Set.of(logbackDep),
                        Set.of(junitDep, logbackDep, slf4jDep)));

        assertThat(fooMetadata.variants).isEqualTo(expectedFooVariants);

        // ***DELINEATOR FOR REVIEW: and
        File barMetadataFilename = bar.buildDir()
                .file("publications/maven/module.json")
                .path()
                .toFile();
        MetadataFile barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile.class);

        Set<MetadataFile.Variant> expectedBarVariants = Set.of(
                createVariant("apiElements", null, Set.of(junitDep, logbackDep, slf4jDep)),
                createVariant(
                        "runtimeElements",
                        Set.of(junitDep),
                        Set.of(junitDep, logbackDep, slf4jDep)));

        assertThat(barMetadata.variants).isEqualTo(expectedBarVariants);
    }

    // ***DELINEATOR FOR REVIEW: can_depend_on_artifact
    @Test
    void can_depend_on_artifact(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation "junit:junit:4.10@zip"
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();
    }

    // ***DELINEATOR FOR REVIEW: direct_test_dependency_that_is_also_a_production_transitive_ends_up_in_production
    @Test
    void direct_test_dependency_that_is_also_a_production_transitive_ends_up_in_production(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                    testImplementation 'org.slf4j:slf4j-api:1.7.25'
                }
                """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        assertThat(rootProject.file("versions.lock").text()).isEqualTo("""
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

                ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

                org.slf4j:slf4j-api:1.7.25 (2 constraints: 8012a437)
                """);
    }

    // ***DELINEATOR FOR REVIEW: does_not_write_lock_file_when_property_gcvSkipWriteLocks_is_set
    @Test
    void does_not_write_lock_file_when_property_gcvSkipWriteLocks_is_set(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
                dependencies {
                    testImplementation 'org.slf4j:slf4j-api:1.7.25'
                }
                """);

        String lockFileContent = """
                # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
                """;

        rootProject.file("versions.lock").overwrite(lockFileContent);

        InvocationResult result = gradle.withArgs("--write-locks", "-PgcvSkipWriteLocks").buildsSuccessfully();

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(lockFileContent);
        assertThat(result).output().contains("Skipped writing lock state");
        assertThat(result).output().doesNotContain("Finished writing lock state");
    }

    // ***DELINEATOR FOR REVIEW: verifyLockfile
    private void verifyLockfile(Path projectDir, String... lines) {
        Path lockfile = projectDir.resolve("gradle.lockfile");
        if (Files.exists(lockfile)) {
            String content;
            try {
                content = Files.readString(lockfile);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Failed to read lockfile", e);
            }
            Arrays.stream(lines).forEach(line -> {
                if (!content.contains(line + "=runtimeClasspath")) {
                    throw new AssertionError(
                            String.format("Expected lockfile to contain '%s=runtimeClasspath' but was: %s", line, content));
                }
            });
        } else {
            Path oldLockfile = projectDir.resolve("gradle/dependency-locks/runtimeClasspath.lockfile");
            if (!Files.exists(oldLockfile)) {
                throw new AssertionError("No lockfile found at " + lockfile + " or " + oldLockfile);
            }
            String content;
            try {
                content = Files.readString(oldLockfile);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Failed to read lockfile", e);
            }
            Arrays.stream(lines).forEach(line -> {
                if (!content.contains(line)) {
                    throw new AssertionError(
                            String.format("Expected lockfile to contain '%s' but was: %s", line, content));
                }
            });
        }
    }

    private void makePlatformPom(MavenRepo repo, String group, String name, String version) {
        Path dir = repo.path().resolve(group).resolve(name).resolve(version);
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("platform-1.0.pom"),
                    """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                              <modelVersion>4.0.0</modelVersion>
                              <packaging>pom</packaging>
                              <groupId>%s</groupId>
                              <artifactId>%s</artifactId>
                              <version>%s</version>
                              <dependencyManagement>
                                <dependencies>
                                </dependencies>
                              </dependencyManagement>
                            </project>
                            """.formatted(group, name, version));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to create platform POM", e);
        }
    }

    private MetadataFile.Dependency createDependency(String group, String module, String version) {
        MetadataFile.Dependency dep = new MetadataFile.Dependency();
        dep.group = group;
        dep.module = module;
        dep.version = java.util.Map.of("requires", version);
        return dep;
    }

    private MetadataFile.Variant createVariant(
            String name,
            Set<MetadataFile.Dependency> dependencies,
            Set<MetadataFile.Dependency> dependencyConstraints) {
        MetadataFile.Variant variant = new MetadataFile.Variant();
        variant.name = name;
        variant.dependencies = dependencies;
        variant.dependencyConstraints = dependencyConstraints;
        return variant;
    }
}
