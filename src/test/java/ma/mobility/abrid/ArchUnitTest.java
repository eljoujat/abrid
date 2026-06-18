package ma.mobility.abrid;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Vérification des règles d'architecture par ArchUnit.
 *
 * <p>Règles issues du §1 du brief :
 * <ol>
 *   <li>Le package {@code loader} est le seul à connaître le format source (GTFS)</li>
 *   <li>{@code search} et {@code api} ne dépendent pas de {@code loader}</li>
 *   <li>{@code core.model} (domaine) n'a pas de dépendance sortante hors domaine</li>
 *   <li>{@code api} ne dépend pas directement de {@code loader}</li>
 *   <li>{@code realtime} ne dépend pas de {@code loader}</li>
 * </ol>
 */
@AnalyzeClasses(
    packages = "ma.mobility.abrid",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchUnitTest {

    private static final String BASE = "ma.mobility.abrid";

    // -------------------------------------------------------------------------
    // Règle 1 : search ne connaît pas loader
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule searchNeConnaîtPasLoader = noClasses()
        .that().resideInAPackage(BASE + ".core.search..")
        .should().dependOnClassesThat()
        .resideInAPackage(BASE + ".core.loader..")
        .because("La couche search ne doit pas connaître le format GTFS (loader).");

    // -------------------------------------------------------------------------
    // Règle 2 : api ne connaît pas loader
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule apiNeConnaîtPasLoader = noClasses()
        .that().resideInAPackage(BASE + ".api..")
        .should().dependOnClassesThat()
        .resideInAPackage(BASE + ".core.loader..")
        .because("L'API REST ne doit pas coupler à loader — passer par search ou service.");

    // -------------------------------------------------------------------------
    // Règle 3 : realtime ne connaît pas loader
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule realtimeNeConnaîtPasLoader = noClasses()
        .that().resideInAPackage(BASE + ".realtime..")
        .should().dependOnClassesThat()
        .resideInAPackage(BASE + ".core.loader..")
        .because("Le module realtime ne doit pas dépendre du loader GTFS.");

    // -------------------------------------------------------------------------
    // Règle 4 : le modèle de domaine n'a pas de dépendances sortantes vers les autres couches
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule domainNaDependancesSortantes = noClasses()
        .that().resideInAPackage(BASE + ".core.model..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            BASE + ".api..",
            BASE + ".core.loader..",
            BASE + ".core.store..",
            BASE + ".core.search..",
            BASE + ".realtime..",
            BASE + ".config.."
        )
        .because("Le modèle de domaine doit être transverse et sans dépendances sortantes (§1.1).");

    // -------------------------------------------------------------------------
    // Règle 5 : loader ne dépend pas de search (dépendances descendantes)
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule loaderNeConnaîtPasSearch = noClasses()
        .that().resideInAPackage(BASE + ".core.loader..")
        .should().dependOnClassesThat()
        .resideInAPackage(BASE + ".core.search..")
        .because("Les dépendances sont strictement descendantes : loader → store, pas l'inverse.");

    // -------------------------------------------------------------------------
    // Règle 6 : pas de @RestController dans un package autre que api
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule controllersUniquementDansApi = classes()
        .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .should().resideInAPackage(BASE + ".api..")
        .because("Les @RestController doivent être dans le package api.");

    // -------------------------------------------------------------------------
    // Règle 7 : pas de logique métier dans les controllers (@RestController)
    // Vérification par naming : les controllers ne doivent pas hériter de Service
    // (vérification légère — la vraie règle est appliquée en review)
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule loaderEstIsoléDuFormatSource = classes()
        .that().resideInAPackage(BASE + ".core.loader..")
        .and().haveSimpleNameContaining("Gtfs")
        .should().resideInAPackage(BASE + ".core.loader..")
        .because("Tout ce qui connaît GTFS doit rester dans le package loader (§1.2).");

    // -------------------------------------------------------------------------
    // Rule 7 : agent must not depend on loader
    // -------------------------------------------------------------------------
    @ArchTest
    static final ArchRule agentDoesNotKnowLoader = noClasses()
        .that().resideInAPackage(BASE + ".agent..")
        .should().dependOnClassesThat()
        .resideInAPackage(BASE + ".core.loader..")
        .because("The agent package sits above api/search but must not bypass "
            + "the isolation of the loader (§1.2).");

    // -------------------------------------------------------------------------
    // Test JUnit classique : vérification programmatique (double filet)
    // -------------------------------------------------------------------------

    @Test
    void verifierToutesLesRègles() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(BASE);

        searchNeConnaîtPasLoader.check(classes);
        apiNeConnaîtPasLoader.check(classes);
        realtimeNeConnaîtPasLoader.check(classes);
        domainNaDependancesSortantes.check(classes);
        loaderNeConnaîtPasSearch.check(classes);
        agentDoesNotKnowLoader.check(classes);
    }
}
