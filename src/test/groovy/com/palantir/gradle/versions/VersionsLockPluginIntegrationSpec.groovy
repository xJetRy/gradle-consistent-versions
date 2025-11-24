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

package com.palantir.gradle.versions

import com.fasterxml.jackson.databind.ObjectMapper
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import one.util.streamex.StreamEx
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS
import static com.palantir.gradle.versions.PomUtils.makePlatformPom

@Unroll
class VersionsLockPluginIntegrationSpec extends IntegrationSpec {

    // ***DELINEATOR FOR REVIEW: PLUGIN_NAME
    static def PLUGIN_NAME = "com.palantir.versions-lock"

    // ***DELINEATOR FOR REVIEW: setup
    void setup() {
        File mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.2.3 -> org.slf4j:slf4j-api:1.7.25",
                "org.slf4j:slf4j-api:1.7.11",
                "org.slf4j:slf4j-api:1.7.20",
                "org.slf4j:slf4j-api:1.7.24",
                "org.slf4j:slf4j-api:1.7.25",
                "junit:junit:4.10",
                "org:test-dep-that-logs:1.0 -> org.slf4j:slf4j-api:1.7.11",
                "org:another-transitive-dependency:3.2.1",
                "org:another-direct-dependency:1.2.3 -> org:another-transitive-dependency:3.2.1",
        )
        makePlatformPom(mavenRepo, "org", "platform", "1.0")

        buildFile << """
            buildscript {
                repositories {
                    mavenCentral()
                }
            }
            plugins {
                id '${PLUGIN_NAME}'
            }
            allprojects {
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
                
                task resolveConfigurations {
                    doLast {
                        if (pluginManager.hasPlugin('java')) {
                            configurations.compileClasspath.resolve()
                            configurations.runtimeClasspath.resolve()
                        }
                    }
                }
            }
        """
    }

    // ***DELINEATOR FOR REVIEW: can_write_locks
    def '#gradleVersionNumber: can write locks'() {
        setup:
        gradleVersion = gradleVersionNumber

        expect:
        runTasks('--write-locks')
        new File(projectDir, "versions.lock").exists()

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: standardSetup
    private def standardSetup() {
        addSubproject('foo', '''
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.24'
            }
        '''.stripIndent(true))

        addSubproject('bar', '''
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api:${project.bar_version}"
            }
        '''.stripIndent(true))
        file("gradle.properties") << "bar_version=1.7.11"

        addSubproject('forced', '''
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api"
            }
            configurations.all {
                resolutionStrategy {
                    force "org.slf4j:slf4j-api:1.7.20"
                }
            }
        '''.stripIndent(true))
    }

    // ***DELINEATOR FOR REVIEW: cannot_resolve_without_a_root_lock_file
    def '#gradleVersionNumber: cannot resolve without a root lock file'() {
        setup:
        gradleVersion = gradleVersionNumber
        standardSetup()

        expect:
        def result = runTasksAndFail('resolveConfigurations')
        result.output.readLines().any {
            it.matches ".*Root lock file '([^']+)' doesn't exist, please run.*"
        }

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: can_resolve_without_a_root_lock_file_if_lock_file_is_ignored
    def '#gradleVersionNumber: can resolve without a root lock file if lock file is ignored'() {
        setup:
        gradleVersion = gradleVersionNumber
        standardSetup()

        expect:
        runTasks('resolveConfigurations', '-PignoreLockFile')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: consolidates_subproject_dependencies
    def '#gradleVersionNumber: consolidates subproject dependencies'() {
        def expectedError = "Locked by versions.lock"
        setup:
        gradleVersion = gradleVersionNumber
        standardSetup()
        buildFile << '''
            subprojects {
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
            }
        '''.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when: "I write locks"
        runTasks('resolveConfigurations', '--write-locks')

        then: "Lock files are consistent with version resolved at root"
        file("versions.lock").text.readLines().any { it.startsWith('org.slf4j:slf4j-api:1.7.24') }

        ["foo", "bar"].each { verifyLockfile(file(it), "org.slf4j:slf4j-api:1.7.24") }

        // ***DELINEATOR FOR REVIEW: then
        then: "Manually forced version overrides unified dependency"
        verifyLockfile(file("forced"), "org.slf4j:slf4j-api:1.7.20")

        // ***DELINEATOR FOR REVIEW: then
        then: "I can resolve configurations"
        runTasks('resolveConfigurations')

        // ***DELINEATOR FOR REVIEW: when
        when: "I make bar's version constraint incompatible with the force"
        BuildResult incompatible = runTasksAndFail("-Pbar_version=1.7.25", 'resolveConfigurations')

        // ***DELINEATOR FOR REVIEW: then
        then: "Resolution fails"
        incompatible.output.contains(expectedError)

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: works_on_just_root_project
    def '#gradleVersionNumber: works on just root project'() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent(true)

        expect:
        runTasks('--write-locks')
        def lines = file('versions.lock').readLines()
        lines.contains('ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)')
        lines.contains('org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: get_a_conflict_even_if_no_lock_files_applied
    def '#gradleVersionNumber: get a conflict even if no lock files applied'() {
        def expectedError = "Locked by versions.lock"
        setup:
        gradleVersion = gradleVersionNumber
        standardSetup()

        // ***DELINEATOR FOR REVIEW: when
        when: "I write locks"
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then: "Root lock file has expected resolution result"
        file("versions.lock").text.readLines().any { it.contains('org.slf4j:slf4j-api:1.7.24') }

        // ***DELINEATOR FOR REVIEW: then
        then: "I can resolve configurations"
        runTasks('resolveConfigurations')

        // ***DELINEATOR FOR REVIEW: when
        when: "I make bar's version constraint incompatible with the force"
        def incompatible = runTasksAndFail("-Pbar_version=1.7.25", 'resolveConfigurations')

        // ***DELINEATOR FOR REVIEW: then
        then: "Resolution fails"
        incompatible.output.contains(expectedError)

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: fails_fast_when_subproject_that_is_depended_on_has_same_name_as_root_project
    def '#gradleVersionNumber: fails fast when subproject that is depended on has same name as root project'() {
        setup:
        gradleVersion = gradleVersionNumber

        def expectedError = "This plugin doesn't work if the root project shares both group and name with a subproject"
        buildFile << """
            allprojects {
                group 'same'
            }
        """.stripIndent(true)
        settingsFile << "rootProject.name = 'foobar'\n"
        addSubproject("foobar", '''
            apply plugin: 'java-library'
        '''.stripIndent(true))
        addSubproject("other", '''
            apply plugin: 'java-library'
            dependencies {
                implementation project(':foobar')
            }
        '''.stripIndent(true))
        // Otherwise the lack of a lock file will throw first
        file('versions.lock') << ""

        expect:
        def error = runTasksAndFail()
        error.output.contains(expectedError)

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: fails_fast_when_multiple_subprojects_share_the_same_coordinate
    def '#gradleVersionNumber: fails fast when multiple subprojects share the same coordinate'() {
        setup:
        gradleVersion = gradleVersionNumber

        def expectedError = "All subprojects must have unique \$group:\$name"
        buildFile << """
            allprojects {
                group 'same'
            }
        """.stripIndent(true)
        // both projects will have name = 'a'
        addSubproject("foo:a")
        addSubproject("bar:a")
        // Otherwise the lack of a lock file will throw first
        file('versions.lock') << ""

        expect:
        def error = runTasksAndFail()
        error.output.contains(expectedError)

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: addSubproject
    @Override
    File addSubproject(String name) {
        File subprojectDir = new File(projectDir, name.replace(":", "/"))
        subprojectDir.mkdirs()
        StreamEx.split(name, ":")
                .scanLeft { left, right -> "$left:$right" }
                .each { subproject -> settingsFile << "include \"${subproject}\"${LINE_END}" }

        return subprojectDir
    }

    // ***DELINEATOR FOR REVIEW: detects_failOnVersionConflict_on_locked_configuration
    def "#gradleVersionNumber: detects failOnVersionConflict on locked configuration"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'    
            configurations.compileClasspath.resolutionStrategy.failOnVersionConflict()
        """.stripIndent(true)
        file('versions.lock').text = ''

        expect:
        def failure = runTasksAndFail()
        failure.output.contains('Must not use failOnVersionConflict')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: ignores_failOnVersionConflict_on_non_locked_configuration
    def "#gradleVersionNumber: ignores failOnVersionConflict on non-locked configuration"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'    
            configurations {
                foo {
                    resolutionStrategy.failOnVersionConflict()
                }
            }
        """.stripIndent(true)
        file('versions.lock').text = ''

        expect:
        runTasks()

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: fails_if_new_dependency_added_that_was_not_in_the_lock_file
    def '#gradleVersionNumber: fails if new dependency added that was not in the lock file'() {
        setup:
        gradleVersion = gradleVersionNumber

        def expectedError = "Found dependencies that were not in the lock state"
        DependencyGraph dependencyGraph = new DependencyGraph("org:a:1.0", "org:b:1.0")
        GradleDependencyGenerator generator = new GradleDependencyGenerator(dependencyGraph)
        def mavenRepo = generator.generateTestMavenRepo()

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.absolutePath}" }
            }
            
            subprojects {
                apply plugin: 'java'
            }
        """.stripIndent(true)

        addSubproject('foo', '''
            dependencies {
                implementation 'org:a:1.0'
            }
        '''.stripIndent(true))

        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: when
        when:
        file('foo/build.gradle') << """
            dependencies {
                implementation 'org:b:1.0'
            }
        """.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: then
        then: 'Check fails because locks are not up to date'
        def failure = runTasksAndFail(':check')
        failure.task(':verifyLocks').outcome == TaskOutcome.FAILED
        failure.output.contains(expectedError)

        // ***DELINEATOR FOR REVIEW: and
        and: 'Can finally write locks once again'
        runTasks('--write-locks')
        runTasks('verifyLocks')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: does_not_fail_if_unifiedClasspath_is_unresolvable
    def '#gradleVersionNumber: does not fail if unifiedClasspath is unresolvable'() {
        setup:
        gradleVersion = gradleVersionNumber

        file('versions.lock') << """\
            org.slf4j:slf4j-api:1.7.11 (0 constraints: 0000000)
        """.stripIndent(true)

        addSubproject('foo', '''
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.20'
            }
        '''.stripIndent(true))

        expect:
        runTasks('dependencies', '--configuration', 'unifiedClasspath')
        runTasks()

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: fails_if_dependency_was_removed_but_still_in_the_lock_file
    def '#gradleVersionNumber: fails if dependency was removed but still in the lock file'() {
        setup:
        gradleVersion = gradleVersionNumber

        def expectedError = "Locked dependencies missing from the resolution result"
        DependencyGraph dependencyGraph = new DependencyGraph("org:a:1.0", "org:b:1.0")
        GradleDependencyGenerator generator = new GradleDependencyGenerator(dependencyGraph)
        def mavenRepo = generator.generateTestMavenRepo()

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.absolutePath}" }
            }
            
            subprojects {
                apply plugin: 'java'
            }
        """.stripIndent(true)

        addSubproject('foo', '''
            dependencies {
                implementation 'org:a:1.0'
                implementation 'org:b:1.0'
            }
        '''.stripIndent(true))

        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: when
        when:
        file('foo/build.gradle').text = """
            dependencies {
                implementation 'org:a:1.0'
            }
        """.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: then
        then: 'Check fails because locks are not up to date'
        def failure = runTasksAndFail(':check')
        failure.task(':verifyLocks').outcome == TaskOutcome.FAILED
        failure.output.contains(expectedError)

        // ***DELINEATOR FOR REVIEW: and
        and: 'Can finally write locks once again'
        runTasks('--write-locks')
        runTasks('verifyLocks')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: why_works
    def "#gradleVersionNumber: why works"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def result = runTasks('why', '--dependency', 'slf4j-api')
        result.output.contains('org.slf4j:slf4j-api:1.7.25')
        result.output.contains('ch.qos.logback:logback-classic -> 1.7.25')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: why_with_hash_works
    def "#gradleVersionNumber: why with hash works"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def result = runTasks('why', '--hash', '400d4d2a') // slf4j-api
        result.output.contains('org.slf4j:slf4j-api:1.7.25')
        result.output.contains('ch.qos.logback:logback-classic -> 1.7.25')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: why_with_comma_delimited_multiple_hashes_works
    def "#gradleVersionNumber: why with comma-delimited multiple hashes works"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
                implementation 'org:another-direct-dependency:1.2.3' // brings in org:another-transitive-dependency:3.2.1
            }
        '''.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def result = runTasks('why', '--hash', '400d4d2a,050d6518') // both transitive dependencies
        result.output.contains('org.slf4j:slf4j-api:1.7.25')
        result.output.contains('ch.qos.logback:logback-classic -> 1.7.25')
        result.output.contains('org:another-transitive-dependency:3.2.1')
        result.output.contains('org:another-direct-dependency -> 3.2.1')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: does_not_fail_if_subproject_evaluated_later_applies_base_plugin_in_own_build_file
    def '#gradleVersionNumber: does not fail if subproject evaluated later applies base plugin in own build file'() {
        setup:
        gradleVersion = gradleVersionNumber

        addSubproject('foo', """
            apply plugin: 'java-library'
            dependencies {
                implementation project(':foo:bar')
            }
        """.stripIndent(true))

        // Need to make sure bar is evaluated after foo, so we're nesting it!
        def subdir = new File(projectDir, 'foo/bar')
        subdir.mkdirs()
        settingsFile << "include ':foo:bar'"
        new File(subdir, 'build.gradle') << """
            apply plugin: 'java-library'
        """.stripIndent(true)

        expect:
        runTasks('--write-locks')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: locks_platform
    def "#gradleVersionNumber: locks platform"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation platform('org:platform:1.0')
            }
        """.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then:
        file('versions.lock').readLines() == [
                '# Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.',
                '',
                'org:platform:1.0 (1 constraints: a5041a2c)',
        ]

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: verifyLocks_is_cacheable
    def "#gradleVersionNumber: verifyLocks is cacheable"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api:\$depVersion"
            }
        """

        file('gradle.properties') << 'depVersion = 1.7.20'

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then: 'verifyLocks is up to date the second time'
        runTasks('verifyLocks').task(':verifyLocks').outcome == TaskOutcome.SUCCESS
        runTasks('verifyLocks').task(':verifyLocks').outcome == TaskOutcome.UP_TO_DATE

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }


    // ***DELINEATOR FOR REVIEW: verifyLocks_current_lock_state_does_not_get_poisoned_by_existing_lock_file
    def "#gradleVersionNumber: verifyLocks current lock state does not get poisoned by existing lock file"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api:\$depVersion"
            }
        """

        file('gradle.properties') << 'depVersion = 1.7.20'

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then: 'verifyLocks fails if we lower the dep version'
        def fail = runTasksAndFail('verifyLocks', '-PdepVersion=1.7.11')

        // ***DELINEATOR FOR REVIEW: and
        and: 'it expects the correct version to be 1.7.11'
        fail.output.contains """\
               > Found dependencies whose dependents changed:
                 -org.slf4j:slf4j-api:1.7.20 (1 constraints: 3c05433b)
                 +org.slf4j:slf4j-api:1.7.11 (1 constraints: 3c05423b)
            """.stripIndent(true)

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: excludes_from_compileOnly_do_not_obscure_real_dependency
    def "#gradleVersionNumber: excludes from compileOnly do not obscure real dependency"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
            configurations.compileOnly {
                // convoluted, but the idea is to exclude a transitive
                exclude group: 'org.slf4j', module: 'slf4j-api'
            }
        """.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: then
        then: 'slf4j-api still appears in the lock file'
        file('versions.lock').readLines() == [
                '# Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.',
                '',
                'ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)',
                '',
                'org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)',
        ]

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: can_resolve_configuration_dependency
    def "#gradleVersionNumber: can resolve configuration dependency"() {
        setup:
        gradleVersion = gradleVersionNumber

        addSubproject("foo", """
            apply plugin: 'java'
            dependencies {
                implementation project(path: ":bar", configuration: "fun") 
            }
        """.stripIndent(true))

        addSubproject("bar", """
            configurations {
                fun
            }
            
            dependencies {
                fun 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        // Make sure that we can still add dependencies to the original 'fun' configuration after resolving lock state.
        //
        // Adding a constraint to 'fun' calls Configuration.preventIllegalMutation() which fails if observedState is
        // GRAPH_RESOLVED or ARTIFACTS_RESOLVED. That would happen if a configuration that extends from it has been
        // resolved.
        buildFile << """
            configurations.unifiedClasspath.incoming.afterResolve {
                project(':bar').dependencies.constraints {
                    fun 'some:other-dep'
                }
            }
        """.stripIndent(true)

        expect:
        runTasks('--write-locks', 'classes')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: inter_project_normal_dependency_works
    def "#gradleVersionNumber: inter-project normal dependency works"() {
        setup:
        gradleVersion = gradleVersionNumber

        addSubproject("foo", """
            apply plugin: 'java'
            dependencies {
                implementation project(":bar") 
            }
        """.stripIndent(true))

        addSubproject("bar", """
            apply plugin: 'java'
        """.stripIndent(true))

        expect:
        runTasks('--write-locks', 'classes')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: test_dependencies_appear_in_a_separate_block
    def "#gradleVersionNumber: test dependencies appear in a separate block"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'           
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'org:test-dep-that-logs:1.0'
            }
        """.stripIndent(true)

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
            
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            
            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)



            [Test dependencies]
            
            org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
        """.stripIndent(true)
        file('versions.lock').text == expected

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: locks_dependencies_from_extra_source_sets_that_end_in_test
    def "#gradleVersionNumber: locks dependencies from extra source sets that end in test"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            sourceSets {
                eteTest
            }           
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'junit:junit:4.10'
                eteTestImplementation 'org:test-dep-that-logs:1.0'
            }
        """.stripIndent(true)

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
            
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            
            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)



            [Test dependencies]
            
            junit:junit:4.10 (1 constraints: d904fd30)
            
            org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
        """.stripIndent(true)
        file('versions.lock').text == expected

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: versionsLock_testProject_works
    def "#gradleVersionNumber: versionsLock.testProject() works"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation 'junit:junit:4.10'
            }
            
            versionsLock.testProject()
        """.stripIndent(true)

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
             


            [Test dependencies]
            
            junit:junit:4.10 (1 constraints: d904fd30)
        """.stripIndent(true)
        file('versions.lock').text == expected

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: constraints_on_production_do_not_affect_scope_of_test_only_dependencies
    def "#gradleVersionNumber: constraints on production do not affect scope of test only dependencies"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                constraints {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                }
                dependencies {
                    testImplementation 'ch.qos.logback:logback-classic'
                }
            }
        """.stripIndent(true)

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
             


            [Test dependencies]
            
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            
            org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)
        """.stripIndent(true)
        file('versions.lock').text == expected

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: published_constraints_are_derived_from_lock_file_with_local_constraints
    def "#gradleVersionNumber: published constraints are derived from lock file (with local constraints)"() {
        setup:
        // Test with local constraints enabled
        file('gradle.properties') << 'com.palantir.gradle.versions.publishLocalConstraints = true'
        gradleVersion = gradleVersionNumber

        buildFile << """
            allprojects {
                apply plugin: 'java'
            }
        """.stripIndent(true)

        String publish = """
            apply plugin: 'maven-publish'
            group = 'com.palantir.published-constraints'
            version = '1.2.3'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
        """.stripIndent(true)

        addSubproject('foo', """
            $publish
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        addSubproject('bar', """
            $publish
            dependencies {
                implementation 'junit:junit:4.10'
            }
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])
        def fooDep = new MetadataFile.Dependency(
                group: 'com.palantir.published-constraints',
                module: 'foo',
                version: [requires: '1.2.3'])
        def barDep = new MetadataFile.Dependency(
                group: 'com.palantir.published-constraints',
                module: 'bar',
                version: [requires: '1.2.3'])

        // ***DELINEATOR FOR REVIEW: then
        then: "foo's metadata file has the right dependency constraints"
        def fooMetadataFilename = new File(projectDir, "foo/build/publications/maven/module.json")
        def fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile)

        fooMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [barDep, junitDep, logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [barDep, junitDep, logbackDep, slf4jDep])
        ] as Set

        // ***DELINEATOR FOR REVIEW: and
        and: "bar's metadata file has the right dependency constraints"
        def barMetadataFilename = new File(projectDir, "bar/build/publications/maven/module.json")
        def barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile)

        barMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [fooDep, junitDep, logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [fooDep, junitDep, logbackDep, slf4jDep]),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: published_constraints_are_derived_from_lock_file_without_local_constraints
    def "#gradleVersionNumber: published constraints are derived from lock file (without local constraints)"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            allprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """.stripIndent(true)

        addSubproject('foo', """
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        addSubproject('bar', """
            dependencies {
                implementation 'junit:junit:4.10'
            }
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])

        // ***DELINEATOR FOR REVIEW: then
        then: "foo's metadata file has the right dependency constraints"
        def fooMetadataFilename = new File(projectDir, "foo/build/publications/maven/module.json")
        def fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile)

        fooMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [junitDep, logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [junitDep, logbackDep, slf4jDep]),
        ] as Set

        // ***DELINEATOR FOR REVIEW: and
        and: "bar's metadata file has the right dependency constraints"
        def barMetadataFilename = new File(projectDir, "bar/build/publications/maven/module.json")
        def barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile)

        barMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [junitDep, logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [junitDep, logbackDep, slf4jDep]),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: can_depend_on_artifact
    def "#gradleVersionNumber: can depend on artifact"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation "junit:junit:4.10@zip"
            }
        """.stripIndent(true)

        expect:
        runTasks("--write-locks")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: direct_test_dependency_that_is_also_a_production_transitive_ends_up_in_production
    def "#gradleVersionNumber: direct test dependency that is also a production transitive ends up in production"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'org.slf4j:slf4j-api:1.7.25'
            }
        """.stripIndent(true)

        expect:
        runTasks("--write-locks")
        file('versions.lock').text == """\
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
            
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            
            org.slf4j:slf4j-api:1.7.25 (2 constraints: 8012a437)
        """.stripIndent(true)

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: does_not_write_lock_file_when_property_gcvSkipWriteLocks_is_set
    def "#gradleVersionNumber: does not write lock file when property 'gcvSkipWriteLocks' is set"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            apply plugin: 'java'
            dependencies {
                testImplementation 'org.slf4j:slf4j-api:1.7.25'
            }
        """.stripIndent(true)

        def lockFileContent = """\
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.
        """.stripIndent(true)

        file('versions.lock').text = lockFileContent

        expect:
        def result = runTasks("--write-locks", "-PgcvSkipWriteLocks")

        file('versions.lock').text == lockFileContent
        assert result.getOutput().contains("Skipped writing lock state")
        assert !result.getOutput().contains("Finished writing lock state")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // ***DELINEATOR FOR REVIEW: verifyLockfile
    boolean verifyLockfile(File projectDir, String... lines) {
        // Gradle 7+ only uses a single lockfile per project:
        // https://docs.gradle.org/current/userguide/upgrading_version_6.html#locking_single
        if (GradleVersion.version(gradleVersion) >= GradleVersion.version("7.0.0")) {
            def lockfile = file("gradle.lockfile", projectDir).text
            lines.each{ assert lockfile.contains(it + "=runtimeClasspath") }
        } else {
            def lockfile = file("gradle/dependency-locks/runtimeClasspath.lockfile", projectDir).text
            lines.each{ assert lockfile.contains(it) }
        }
    }
}
