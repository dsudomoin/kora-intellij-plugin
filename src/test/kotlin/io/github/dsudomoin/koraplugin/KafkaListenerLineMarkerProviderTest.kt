package io.github.dsudomoin.koraplugin

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dsudomoin.koraplugin.config.KafkaListenerLineMarkerProvider
import org.jetbrains.kotlin.lexer.KtTokens

class KafkaListenerLineMarkerProviderTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val provider = KafkaListenerLineMarkerProvider()

    private fun configureKafkaAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/kafka/common/annotation/KafkaListener.java",
        )
    }

    private fun collectMarkers(elements: List<PsiElement>): List<LineMarkerInfo<*>> {
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements.toMutableList(), result)
        return result
    }

    fun `test gutter icon on java KafkaListener annotation`() {
        configureKafkaAnnotations()

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            public class MyConsumer {
                @KafkaListener("path.to.kafka.consumer")
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java).toList()
        val markers = collectMarkers(identifiers)

        assertEquals(1, markers.size)
        assertEquals("KafkaListener", markers.single().element?.text)
        assertEquals("Navigate to Kafka config key", markers.single().lineMarkerTooltip)
    }

    fun `test no gutter icon without KafkaListener annotation`() {
        configureKafkaAnnotations()

        myFixture.configureByText(
            "PlainService.java",
            """
            public class PlainService {
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java).toList()
        assertTrue(collectMarkers(identifiers).isEmpty())
    }

    fun `test gutter icon on kotlin KafkaListener annotation`() {
        configureKafkaAnnotations()

        myFixture.configureByText(
            "KtConsumer.kt",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener

            class KtConsumer {
                @KafkaListener("kt.topic")
                fun consume(msg: String) {}
            }
            """.trimIndent(),
        )

        val identifiers = PsiTreeUtil.collectElements(myFixture.file) {
            it.node?.elementType == KtTokens.IDENTIFIER
        }.toList()
        val markers = collectMarkers(identifiers)

        assertEquals(1, markers.size)
        assertEquals("KafkaListener", markers.single().element?.text)
    }
    fun `test single gutter icon on java KafkaListener with named value`() {
        configureKafkaAnnotations()

        myFixture.configureByText(
            "NamedConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            public class NamedConsumer {
                @KafkaListener(value = "path.to.kafka.consumer")
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java).toList()
        val markers = collectMarkers(identifiers)

        assertEquals(1, markers.size)
        assertEquals("KafkaListener", markers.single().element?.text)
    }

    fun `test single gutter icon on kotlin KafkaListener with named value`() {
        configureKafkaAnnotations()

        myFixture.configureByText(
            "KtNamedConsumer.kt",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener

            class KtNamedConsumer {
                @KafkaListener(value = "kt.consumer")
                fun consume(msg: String) {}
            }
            """.trimIndent(),
        )

        val identifiers = PsiTreeUtil.collectElements(myFixture.file) {
            it.node?.elementType == KtTokens.IDENTIFIER
        }.toList()
        val markers = collectMarkers(identifiers)

        assertEquals(1, markers.size)
        assertEquals("KafkaListener", markers.single().element?.text)
    }
}
