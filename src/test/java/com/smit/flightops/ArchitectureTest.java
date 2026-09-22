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
 * <p>Each rule is already true of the code. It fails the build when a later change
 * breaks it, which a reviewer would otherwise have to check on every pull request.
 * {@link ImportOption.DoNotIncludeTests} keeps test fixtures, which read the clock,
 * out of scope. No rule has a suppression, and there is no frozen violation store.
 */
@AnalyzeClasses(packages = "com.smit.flightops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The dependency graph. Only the four layers that can be violated upwards carry
     * a restriction; {@code dto}, {@code exception}, {@code security} and
     * {@code validation} are leaves anything may read.
     * {@code consideringOnlyDependenciesInLayers} exempts
     * {@code FlightOpsServiceApplication}, which sits in no layer.
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
     * Controllers speak DTOs. An entity on the wire serialises lazy proxies, publishes
     * every new column and would put {@code idempotencyKey} back in the response.
     */
    @ArchTest
    static final ArchRule controllers_do_not_touch_entities =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..entity..")
                    .as("controllers must map to a DTO rather than expose an entity");

    /**
     * Constructor injection only, so dependencies are final, a unit test can use
     * {@code new}, and a growing collaborator list is visible in the signature.
     */
    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .orShould().beAnnotatedWith("jakarta.annotation.Resource")
            .orShould().beAnnotatedWith("jakarta.inject.Inject")
            .as("dependencies arrive through the constructor, not through reflection");

    /** SLF4J only: a second logging API writes lines that bypass the JSON formatter. */
    @ArchTest
    static final ArchRule no_java_util_logging =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /**
     * Nothing writes to {@code System.out} or {@code System.err}: such lines lose the
     * level, logger name, MDC and correlation id.
     */
    @ArchTest
    static final ArchRule no_standard_streams =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    /**
     * Repositories stay Spring Data interfaces, so their transaction and lock
     * semantics can be read off the method signature.
     */
    @ArchTest
    static final ArchRule repositories_are_interfaces = classes()
            .that().resideInAPackage("..repository..")
            .should().beInterfaces()
            .andShould().haveSimpleNameEndingWith("Repository")
            .as("repositories stay declarative Spring Data interfaces");

    /**
     * Transactions open only in {@code service}. The booking path holds a row lock
     * and needs its transaction to end promptly; one on a controller would wrap the
     * whole request. {@code jakarta.transaction.Transactional} is checked too, because
     * Spring honours it and an IDE import can pick it.
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
     * One source of time: the {@link java.time.Clock} bean. The only exemption is
     * {@code Booking}, whose {@code createdAt} initialiser has nothing to inject into
     * because Hibernate constructs the entity too. The exemption names that class,
     * not its package. {@code System.currentTimeMillis} and the {@code LocalDate}
     * family are the accidental ways round the rule.
     */
    @ArchTest
    static final ArchRule the_wall_clock_is_read_only_by_entities =
            noClasses().that().doNotHaveFullyQualifiedName("com.smit.flightops.entity.Booking")
                    .should().callMethod(Instant.class, "now")
                    .orShould().callMethod(LocalDate.class, "now")
                    .orShould().callMethod(LocalDateTime.class, "now")
                    .orShould().callMethod(System.class, "currentTimeMillis")
                    .as("time comes from the injected Clock; only Booking.createdAt reads the wall clock");

    /**
     * No servlet or Spring Web types below the controller, so a service stays callable
     * from the outbox drain, a scheduled job or a test with no request on the thread.
     * {@code security} is outside the check: {@link com.smit.flightops.security.ErrorResponseWriter}
     * writes the raw response because Spring Security rejects requests before
     * {@code DispatcherServlet}.
     */
    @ArchTest
    static final ArchRule no_web_types_below_the_controller =
            noClasses().that().resideInAnyPackage("..service..", "..entity..", "..repository..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..",
                                        "org.springframework.http..")
                    .as("services, entities and repositories know nothing about HTTP");
}
