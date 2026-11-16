package kh.cmt.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;

@AnalyzeClasses(packages = "kh.cmt", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /** controller -> use case -> domain -> repository. Controllers (web layer) never touch persistence. */
    @ArchTest
    static final ArchRule controllersNeverCallRepositories = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    /** Time comes from the injected Clock (Asia/Phnom_Penh), never from the system default. */
    @ArchTest
    static final ArchRule noDirectNow = noClasses()
            .should().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalTime.class, "now")
            .orShould().callMethod(Instant.class, "now")
            .allowEmptyShould(true);

    /**
     * Sensitive fields (step 3.4): Excel files are only written through ExcelExporter, which leaves out the columns the
     * caller may not see.
     */
    @ArchTest
    static final ArchRule excelOnlyThroughTheExporter = noClasses()
            .that().resideOutsideOfPackage("kh.cmt.shared.export..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.poi..")
            .allowEmptyShould(true);

    /**
     * Record scope (step 3.3): every entity that is a person or belongs to one takes part in the scope filter, so a
     * scoped user never sees people outside their scope in any list, search or export.
     */
    @ArchTest
    static final ArchRule personRecordsAreScoped = classes()
            .that().areAnnotatedWith(Entity.class)
            .and(new DescribedPredicate<JavaClass>("are people or belong to a person") {
                @Override
                public boolean test(JavaClass entity) {
                    return entity.getSimpleName().equals("Person")
                            || entity.getAllFields().stream().anyMatch(f -> f.getName().equals("personId") || f.getName().equals("person"));
                }
            })
            .should(new ArchCondition<JavaClass>("declare the record scope filter (ScopeFilter.NAME)") {
                @Override
                public void check(JavaClass entity, ConditionEvents events) {
                    Class<?> type = entity.reflect();
                    boolean scoped = Arrays.stream(type.getAnnotationsByType(Filter.class)).anyMatch(f -> f.name().equals(ScopeFilter.NAME));
                    if (!scoped) {
                        events.add(SimpleConditionEvent.violated(entity, entity.getName() + " has no @Filter(name = ScopeFilter.NAME)"));
                    }
                }
            })
            .allowEmptyShould(true);
}
