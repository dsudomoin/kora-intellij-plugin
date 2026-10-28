package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KafkaTopicCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureKafkaAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/kafka/common/annotation/KafkaListener.java",
        )
    }

    fun `test collects topics from method level listeners`() {
        configureKafkaAnnotations()

        myFixture.addFileToProject(
            "OrderConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            public class OrderConsumer {
                @KafkaListener("orders")
                public void consume(String msg) {}

                @KafkaListener("payments")
                public void consumePayment(String msg) {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val topics = KafkaTopicCollector.collect(project)
        assertEquals(2, topics.size)

        val orders = topics.first { it.configPath == "orders" }
        val target = orders.targets.single()
        assertEquals("OrderConsumer", target.classFqn)
        assertEquals("consume", target.methodName)
    }

    fun `test collects topics from kotlin listener`() {
        configureKafkaAnnotations()

        myFixture.configureByText(
            "KtConsumer.kt",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener

            class KtConsumer {
                @KafkaListener("kt-orders")
                fun consume(msg: String) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val topics = KafkaTopicCollector.collect(project)
        assertEquals(1, topics.size)
        assertEquals("kt-orders", topics.single().configPath)
        assertEquals("KtConsumer", topics.single().targets.single().classFqn)
        assertEquals("consume", topics.single().targets.single().methodName)
    }

    fun `test same topic from two listeners merges into one topic with two targets`() {
        configureKafkaAnnotations()

        myFixture.addFileToProject(
            "FirstConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            public class FirstConsumer {
                @KafkaListener("orders")
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "SecondConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            public class SecondConsumer {
                @KafkaListener("orders")
                public void consumeToo(String msg) {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val topics = KafkaTopicCollector.collect(project)
        assertEquals(1, topics.size)
        assertEquals("orders", topics.single().configPath)
        assertEquals(2, topics.single().targets.size)
    }

    fun `test class level listener navigates to the class`() {
        configureKafkaAnnotations()

        myFixture.addFileToProject(
            "LegacyConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            @KafkaListener("legacy-topic")
            public class LegacyConsumer {
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val topics = KafkaTopicCollector.collect(project)
        assertEquals(1, topics.size)
        val target = topics.single().targets.single()
        assertEquals("LegacyConsumer", target.classFqn)
        assertNull(target.methodName)
    }

    fun `test returns empty when annotation class is not resolvable`() {
        myFixture.addFileToProject(
            "LonelyConsumer.java",
            """
            public class LonelyConsumer {
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        assertTrue(KafkaTopicCollector.collect(project).isEmpty())
    }
}
