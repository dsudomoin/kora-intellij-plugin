package ru.dsudomoin.koraplugin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.resolve.KoraBeanNavigator
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver

class KoraProviderResolverTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/common/KoraApp.java",
            "ru/tinkoff/kora/common/Module.java",
            "ru/tinkoff/kora/common/KoraSubmodule.java",
            "ru/tinkoff/kora/common/Tag.java",
            "ru/tinkoff/kora/common/annotation/Generated.java",
            "ru/tinkoff/kora/application/graph/All.java",
        )
    }

    fun `test navigate from Component constructor parameter to Component provider`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImpl implements MyService {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyController {
                public MyController(MyService my<caret>Service) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to MyServiceImpl",
            targets.any { it is PsiClass && it.name == "MyServiceImpl" },
        )
    }

    fun `test navigate from Module factory method parameter to provider`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyRepository.java",
            """
            public interface MyRepository {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyRepositoryImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyRepositoryImpl implements MyRepository {
                public MyRepositoryImpl() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createService(MyRepository my<caret>Repo) {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to MyRepositoryImpl",
            targets.any { it is PsiClass && it.name == "MyRepositoryImpl" },
        )
    }

    fun `test navigate with Tag filter`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyTag.java",
            """
            public class MyTag {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "TaggedServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            @Tag(MyTag.class)
            public class TaggedServiceImpl implements MyService {
                public TaggedServiceImpl() {}
            }
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "UntaggedServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class UntaggedServiceImpl implements MyService {
                public UntaggedServiceImpl() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            public class MyConsumer {
                public MyConsumer(@Tag(MyTag.class) MyService my<caret>Service) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertEquals("Should find only tagged provider", 1, targets.size)
        assertTrue(
            "Expected to navigate to TaggedServiceImpl",
            targets.any { it is PsiClass && it.name == "TaggedServiceImpl" },
        )
    }

    fun `test multiple candidates returned`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "ServiceImplA.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class ServiceImplA implements MyService {
                public ServiceImplA() {}
            }
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "ServiceImplB.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class ServiceImplB implements MyService {
                public ServiceImplB() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            public class MyConsumer {
                public MyConsumer(@Tag(Tag.Any.class) MyService my<caret>Service) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertTrue("Should find at least 2 providers", targets.size >= 2)
    }

    fun `test All type unwrapping`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "ServiceImplA.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class ServiceImplA implements MyService {
                public ServiceImplA() {}
            }
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "ServiceImplB.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class ServiceImplB implements MyService {
                public ServiceImplB() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.application.graph.All;

            @Component
            public class MyConsumer {
                public MyConsumer(All<MyService> all<caret>Services) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertTrue("Should find at least 2 providers for All<MyService>", targets.size >= 2)
    }

    fun `test factory method provider found`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createMyService() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyConsumer {
                public MyConsumer(MyService my<caret>Service) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to factory method createMyService",
            targets.any { it is PsiMethod && it.name == "createMyService" },
        )
    }

    fun `test factory method prioritized over Component class`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImpl implements MyService {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )

        // Simulates generated SubmoduleImpl (annotated with @Generated, not @Module)
        myFixture.addFileToProject(
            "MyModuleSubmoduleImpl.java",
            """
            import ru.tinkoff.kora.common.annotation.Generated;

            @Generated("ru.tinkoff.kora.kora.app.ksp.KoraSubmoduleProcessor")
            public interface MyModuleSubmoduleImpl {
                default MyServiceImpl _component0() {
                    return new MyServiceImpl();
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyConsumer {
                public MyConsumer(MyService my<caret>Service) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertEquals("Should find only factory method, not @Component class", 1, targets.size)
        assertTrue(
            "Expected to navigate to factory method _component0",
            targets.any { it is PsiMethod && it.name == "_component0" },
        )
    }

    fun `test navigate from unannotated module inherited by KoraApp`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImpl implements MyService {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )

        // @KoraApp extends the unannotated module
        myFixture.addFileToProject(
            "MyApp.java",
            """
            import ru.tinkoff.kora.common.KoraApp;

            @KoraApp
            public interface MyApp extends ConfigModule {}
            """.trimIndent(),
        )

        // Unannotated module interface with factory methods — caret here
        myFixture.configureByText(
            "ConfigModule.java",
            """
            public interface ConfigModule {
                default MyService createService(MyServiceImpl my<caret>Impl) {
                    return myImpl;
                }
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to MyServiceImpl @Component class",
            targets.any { it is PsiClass && it.name == "MyServiceImpl" },
        )
    }

    fun `test Json annotation generates JsonReader provider`() {
        configureAnnotations()
        myFixture.configureByFiles(
            "ru/tinkoff/kora/json/common/annotation/Json.java",
            "ru/tinkoff/kora/json/common/JsonReader.java",
        )

        myFixture.addFileToProject(
            "TelegramUserData.java",
            """
            import ru.tinkoff.kora.json.common.annotation.Json;

            @Json
            public class TelegramUserData {
                public long telegramId;
                public String firstName;
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "AuthorizePlayerOperation.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.json.common.JsonReader;

            @Component
            public class AuthorizePlayerOperation {
                public AuthorizePlayerOperation(JsonReader<TelegramUserData> telegram<caret>Reader) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to @Json-annotated TelegramUserData class",
            targets.any { it is PsiClass && it.name == "TelegramUserData" },
        )
    }

    fun `test Json annotation generates JsonWriter provider`() {
        configureAnnotations()
        myFixture.configureByFiles(
            "ru/tinkoff/kora/json/common/annotation/Json.java",
            "ru/tinkoff/kora/json/common/JsonWriter.java",
        )

        myFixture.addFileToProject(
            "TelegramUserData.java",
            """
            import ru.tinkoff.kora.json.common.annotation.Json;

            @Json
            public class TelegramUserData {
                public long telegramId;
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MySerializer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.json.common.JsonWriter;

            @Component
            public class MySerializer {
                public MySerializer(JsonWriter<TelegramUserData> telegram<caret>Writer) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to @Json-annotated TelegramUserData class",
            targets.any { it is PsiClass && it.name == "TelegramUserData" },
        )
    }

    fun `test JsonReader annotation generates only JsonReader provider`() {
        configureAnnotations()
        myFixture.configureByFiles(
            "ru/tinkoff/kora/json/common/annotation/JsonReader.java",
            "ru/tinkoff/kora/json/common/JsonReader.java",
        )

        myFixture.addFileToProject(
            "MyData.java",
            """
            import ru.tinkoff.kora.json.common.annotation.JsonReader;

            @JsonReader
            public class MyData {
                public String value;
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.json.common.JsonReader;

            @Component
            public class MyConsumer {
                public MyConsumer(JsonReader<MyData> data<caret>Reader) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to @JsonReader-annotated MyData class",
            targets.any { it is PsiClass && it.name == "MyData" },
        )
    }

    fun `test JsonWriter annotation generates only JsonWriter provider`() {
        configureAnnotations()
        myFixture.configureByFiles(
            "ru/tinkoff/kora/json/common/annotation/JsonWriter.java",
            "ru/tinkoff/kora/json/common/JsonWriter.java",
        )

        myFixture.addFileToProject(
            "MyData.java",
            """
            import ru.tinkoff.kora.json.common.annotation.JsonWriter;

            @JsonWriter
            public class MyData {
                public String value;
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.json.common.JsonWriter;

            @Component
            public class MyConsumer {
                public MyConsumer(JsonWriter<MyData> data<caret>Writer) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertNotEmpty(targets)
        assertTrue(
            "Expected to navigate to @JsonWriter-annotated MyData class",
            targets.any { it is PsiClass && it.name == "MyData" },
        )
    }

    fun `test generic factory method finds concrete injection sites`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "JsonReader.java",
            """
            public interface JsonReader<T> {
                T read(String value);
            }
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "JdbcResultColumnMapper.java",
            """
            public interface JdbcResultColumnMapper<T> {
                T map(Object rs, int index);
            }
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "RaceSlot.java",
            """
            public class RaceSlot {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "JdbcMappersModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface JdbcMappersModule {
                default <T> JdbcResultColumnMapper<T> jsonReaderColumnMapper(JsonReader<T> reader) {
                    return null;
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "RaceSlotRepository.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class RaceSlotRepository {
                public RaceSlotRepository(JdbcResultColumnMapper<RaceSlot> raceSlotMapper) {}
            }
            """.trimIndent(),
        )

        val usages = KoraBeanNavigator.resolveFactoryMethodUsages(
            project, "JdbcMappersModule", "jsonReaderColumnMapper",
        )

        assertNotEmpty(usages)
        assertTrue(
            "Expected to find injection site for JdbcResultColumnMapper<RaceSlot>",
            usages.any {
                val parent = it.parent
                parent is PsiParameter && parent.name == "raceSlotMapper"
            },
        )
    }

    fun `test non-injection context returns empty`() {
        configureAnnotations()

        myFixture.configureByText(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass(String some<caret>Param) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = KoraProviderResolver.resolve(element)

        assertTrue("Should return empty for non-Kora class", targets.isEmpty())
    }
}
