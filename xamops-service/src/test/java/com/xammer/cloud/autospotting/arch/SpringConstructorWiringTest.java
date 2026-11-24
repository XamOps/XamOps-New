package com.xammer.cloud.autospotting.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Regression guard (G3 boot failure): a Spring stereotype bean with more than one constructor needs
 * exactly one {@code @Autowired} constructor. Without it Spring falls back to a no-arg constructor and
 * the application fails to start, which no unit test notices because tests call constructors directly.
 *
 * <p>Scope: the AutoSpotting packages and the packages of the two controllers that tripped it. Other
 * pre-existing offenders outside this scope are deliberately not fixed here.
 */
class SpringConstructorWiringTest {
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final List<String> STEREOTYPES = List.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.context.annotation.Configuration");

    @Test
    void multiConstructorSpringBeansHaveExactlyOneAutowiredConstructor() {
        JavaClasses scoped = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.xammer.cloud");
        classes().that().resideInAnyPackage("..autospotting..")
                .and(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("are concrete Spring stereotype beans") {
                    @Override
                    public boolean test(JavaClass c) {
                        return !c.isInterface() && !c.isAnnotation()
                                && !c.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
                                && STEREOTYPES.stream().anyMatch(s -> c.isMetaAnnotatedWith(s));
                    }
                })
                .should(new ArchCondition<JavaClass>("have exactly one @Autowired constructor when they declare more than one") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        int ctors = c.getConstructors().size();
                        if (ctors <= 1) {
                            return;
                        }
                        long autowired = c.getConstructors().stream()
                                .filter(k -> k.isAnnotatedWith(AUTOWIRED)).count();
                        if (autowired != 1) {
                            events.add(SimpleConditionEvent.violated(c, c.getName() + " declares " + ctors
                                    + " constructors with " + autowired + " @Autowired (needs exactly 1)"));
                        }
                    }
                })
                .allowEmptyShould(false)
                .check(scoped);
    }
}
