package com.smit.flightops;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * The rules the package layout is supposed to express, made executable.
 *
 * <p><b>Why this file earns its place.</b> Every rule below is already true of
 * the code as written, so none of them fixes a bug today. What they fix is the
 * six-months-from-now version of the codebase, where somebody in a hurry injects
 * a repository straight into a controller because it is two lines instead of
 * twenty, and nothing complains. Conventions that live only in a README are
 * conventions that decay; conventions that fail the build are conventions. Each
 * rule here is one that a reviewer would otherwise have to remember to look for
 * on every pull request, which is exactly the kind of work worth automating.
 *
 * <p>{@link ImportOption.DoNotIncludeTests} matters more than it looks.
 * The test sources call {@code Instant.now()} constantly to build fixtures, and
 * they are meant to — a test is allowed to know what time it is. Without this
 * option the clock rule would fail on its own test suite.
 *
 * <p>Nine rules, no suppressions, no {@code archunit.properties} with
 * {@code freeze} in it. A frozen violation store is a good tool for retrofitting
 * rules onto a large legacy codebase and a bad habit on a small clean one: it
 * turns "this rule passes" into "this rule passes except for the list you have
 * not read".
 */
@AnalyzeClasses(packages = "com.smit.flightops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The dependency graph, stated once so it cannot drift.
     *
     * <p>Only the four rules that can be violated upwards are declared. The
     * supporting packages — {@code dto}, {@code exception}, {@code security},
     * {@code validation} — are named as layers so they are legal origins, but
     * they carry no access restriction of their own: they are leaves that
     * anything may read, and pinning who reads a DTO would be busywork.
     *
     * <p>{@code consideringOnlyDependenciesInLayers} exempts
     * {@code FlightOpsServiceApplication}, which sits at the root in no layer at
     * all. It holds a {@code main} method and three annotations; making the
     * entry point obey the layering it bootstraps is a rule with no content.
     */
    @ArchTest
    static final ArchRule layers_are_respected = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Entity").definedBy("..entity..")
            .layer("Config").definedBy("..config..")
            .layer("Dto").definedBy("..dto..")
            .layer("Exception").definedBy("..exception..")
            .layer("Security").definedBy("..security..")
            .layer("Validation").definedBy("..validation..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Config")
            .whereLayer("Entity").mayOnlyBeAccessedByLayers(
                    "Service", "Repository", "Dto", "Exception", "Config")

            .as("the dependency graph only ever points downwards");

    /**
     * Controllers speak DTOs, never entities.
     *
     * <p>Returning a {@code Booking} from a handler works right up until it
     * does not: a LAZY association serialises as a proxy and blows up with
     * {@code LazyInitializationException} outside the transaction, a new column
     * silently becomes part of the public API, and {@code idempotencyKey} —
     * which this codebase deliberately stopped returning — would be back on the
     * wire. {@link com.smit.flightops.dto.BookingDto} exists precisely so that
     * the persistence shape and the wire shape can move independently.
     */
    @ArchTest
    static final ArchRule controllers_do_not_touch_entities =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..entity..")
                    .as("controllers must map to a DTO rather than expose an entity");

    /**
     * Constructor injection only.
     *
     * <p>A field-injected dependency cannot be final, cannot be set by a plain
     * {@code new} in a unit test, and hides from the compiler how many
     * collaborators a class has grown — which is the signal you want loudest
     * when a class is becoming too big. Every class in this codebase takes its
     * dependencies through the constructor; this keeps it that way.
     */
    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .orShould().beAnnotatedWith("jakarta.annotation.Resource")
            .orShould().beAnnotatedWith("jakarta.inject.Inject")
            .as("dependencies arrive through the constructor, not through reflection");

    /**
     * SLF4J, not {@code java.util.logging}. Two logging APIs in one process
     * means two configurations, and the one nobody configured is the one that
     * writes unstructured lines straight past the JSON formatter.
     */
    @ArchTest
    static final ArchRule no_java_util_logging =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /**
     * Nothing writes to {@code System.out} or {@code System.err}.
     *
     * <p>A stray {@code printStackTrace} is the classic version: it loses the
     * level, the logger name, the MDC and the correlation id, and in a container
     * it lands in the same stream as the real logs with none of the structure
     * that makes them searchable.
     */
    @ArchTest
    static final ArchRule no_standard_streams =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    /**
     * Repositories are Spring Data interfaces, named for what they are.
     *
     * <p>The moment one becomes a class with hand-written JDBC in it, the
     * transaction and lock semantics the service layer relies on stop being
     * something you can read off the method signature.
     */
    @ArchTest
    static final ArchRule repositories_are_interfaces = classes()
            .that().resideInAPackage("..repository..")
            .should().beInterfaces()
            .andShould().haveSimpleNameEndingWith("Repository")
            .as("repositories stay declarative Spring Data interfaces");

    /**
     * Transaction boundaries are opened in one package and only one.
     *
     * <p>This is the rule with the most operational weight behind it. The
     * booking write path takes a pessimistic row lock and relies on the
     * transaction ending promptly; {@code OutboxWriter} is
     * {@code Propagation.MANDATORY} precisely so it can never open one of its
     * own. A {@code @Transactional} on a controller method would wrap the whole
     * request — HTTP parsing, validation, serialisation — in a database
     * transaction, and the first sign of it would be lock timeouts under load.
     *
     * <p>Both annotation styles are checked. Spring honours
     * {@code jakarta.transaction.Transactional} too, and someone letting the
     * IDE pick an import is the realistic way the wrong one gets in.
     */
    @ArchTest
    static final ArchRule transactions_are_opened_only_in_the_service_layer =
            CompositeArchRule.of(
                            noClasses().that().resideOutsideOfPackage("..service..")
                                    .should().beAnnotatedWith(Transactional.class)
                                    .orShould().beAnnotatedWith("jakarta.transaction.Transactional"))
                    .and(noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage("..service..")
                            .should().beAnnotatedWith(Transactional.class)
                            .orShould().beAnnotatedWith("jakarta.transaction.Transactional"))
                    .as("@Transactional appears only in the service package");

    /**
     * One source of time: the {@link java.time.Clock} bean.
     *
     * <p>{@code Instant.now()} inside a method is a dependency a test cannot
     * reach, which is why the error-body timestamp, the seeded departure times
     * and the outbox timestamps all take the clock instead. The single exception
     * is {@code Booking.createdAt}, which initialises its field inline — an
     * entity is constructed by Hibernate as well as by us, so there is nowhere
     * to inject anything. That is why this rule is scoped to
     * {@code ..entity..} rather than written as a blanket ban with a
     * suppression.
     *
     * <p>{@code System.currentTimeMillis} and the {@code LocalDate} family are
     * listed for the same reason: they are the ways this rule gets worked around
     * by accident rather than on purpose.
     */
    @ArchTest
    static final ArchRule the_wall_clock_is_read_only_by_entities =
            noClasses().that().resideOutsideOfPackage("..entity..")
                    .should().callMethod(Instant.class, "now")
                    .orShould().callMethod(LocalDate.class, "now")
                    .orShould().callMethod(LocalDateTime.class, "now")
                    .orShould().callMethod(System.class, "currentTimeMillis")
                    .as("time comes from the injected Clock, not from the wall clock");

    /**
     * The web tier stops at the controller and the security package.
     *
     * <p>A {@code HttpServletRequest} reaching down into a service is how a
     * service stops being callable from the outbox drain, from a scheduled job
     * or from a test — anything without a live request bound to the thread. It
     * is also how a {@code @Scheduled} method starts throwing
     * {@code IllegalStateException: No thread-bound request found} at three in
     * the morning rather than during review.
     *
     * <p>{@code security} is left out of the check on purpose: it is servlet
     * filter code by definition, and
     * {@link com.smit.flightops.security.ErrorResponseWriter} writes to the raw
     * response precisely because Spring Security rejects a request before
     * {@code DispatcherServlet} is ever involved.
     */
    @ArchTest
    static final ArchRule no_web_types_below_the_controller =
            noClasses().that().resideInAnyPackage("..service..", "..entity..", "..repository..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..",
                                        "org.springframework.http..")
                    .as("services, entities and repositories know nothing about HTTP");
}
