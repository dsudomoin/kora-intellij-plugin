package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KafkaPublisherCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configurePublisherAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/kafka/common/annotation/KafkaPublisher.java",
        )
    }

    fun `test collects topics from publisher interface`() {
        configurePublisherAnnotations()

        myFixture.addFileToProject(
            "EventPublisher.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaPublisher;

            @KafkaPublisher("kafka.producer.events")
            public interface EventPublisher {
                @KafkaPublisher.Topic("orders")
                void sendOrder(String key, String order);

                @KafkaPublisher.Topic("payments")
                void sendPayment(String key, String payment);

                void notATopic(String value);
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val publishers = KafkaPublisherCollector.collect(project)
        assertEquals(1, publishers.size)

        val publisher = publishers.single()
        assertEquals("EventPublisher", publisher.publisherFqn)
        assertEquals("kafka.producer.events", publisher.configPath)
        assertEquals(listOf("orders", "payments"), publisher.topics.map { it.configPath })
        assertEquals("sendOrder", publisher.topics.first().target.methodName)
    }

    fun `test collects topics from kotlin publisher`() {
        configurePublisherAnnotations()

        myFixture.configureByText(
            "KtPublisher.kt",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaPublisher

            @KafkaPublisher("kt.producer")
            interface KtPublisher {
                @KafkaPublisher.Topic("kt-topic")
                fun send(key: String, value: String)
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val publishers = KafkaPublisherCollector.collect(project)
        assertEquals(1, publishers.size)
        assertEquals(listOf("kt-topic"), publishers.single().topics.map { it.configPath })
        assertEquals("send", publishers.single().topics.single().target.methodName)
    }

    fun `test publisher without topic methods is skipped`() {
        configurePublisherAnnotations()

        myFixture.addFileToProject(
            "EmptyPublisher.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaPublisher;

            @KafkaPublisher("kafka.producer.empty")
            public interface EmptyPublisher {
                void notATopic(String value);
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        assertTrue(KafkaPublisherCollector.collect(project).isEmpty())
    }

    fun `test returns empty when annotation class is not resolvable`() {
        myFixture.addFileToProject(
            "Lonely.java",
            """
            public interface Lonely {
                void send(String value);
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        assertTrue(KafkaPublisherCollector.collect(project).isEmpty())
    }
}
