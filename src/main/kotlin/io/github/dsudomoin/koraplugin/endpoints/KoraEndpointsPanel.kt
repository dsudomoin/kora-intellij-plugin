package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.Alarm
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil
import java.awt.Color
import java.awt.event.MouseEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

/**
 * Tool window content: tree of HTTP endpoints (grouped by owner: controller class /
 * delegate implementation / spec file) and incoming Kafka topics. The panel holds no PSI —
 * only deflated targets — and re-resolves them at click time via [EndpointNavigation].
 */
internal class KoraEndpointsPanel(private val project: Project) : SimpleToolWindowPanel(true), Disposable {

    private val tree = SimpleTree()
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Kora"))
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val inFlight = AtomicBoolean(false)
    private var lastStamp = -1L
    private var seenStamp = -1L
    private var shown: KoraEndpointsSnapshot? = null

    init {
        tree.model = treeModel
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeSpeedSearch.installOn(tree)
        tree.cellRenderer = EndpointCellRenderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                activateSelectedNode()
                return true
            }
        }.installOn(tree)

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(KORA_ENDPOINTS_TOOL_WINDOW_ID, DefaultActionGroup(RefreshAction()), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(JBScrollPane(tree))

        showStatus("Loading Kora endpoints…")
        refresh()
        schedulePoll()
    }

    fun refresh() {
        if (project.isDisposed) return
        when {
            // Keep the last tree during reindexing (e.g. after KSP regeneration) instead of wiping it.
            DumbService.isDumb(project) -> if (shown == null) showStatus("Waiting for indexes to finish…")
            !KoraLibraryUtil.hasKoraLibrary(project) -> showStatus("Add a Kora dependency to see Kora endpoints")
            inFlight.compareAndSet(false, true) -> KoraEndpointsAggregator.collectAsync(project, this) { snapshot ->
                inFlight.set(false)
                if (snapshot == null) {
                    showStatus("Failed to load Kora endpoints — see IDE log")
                    return@collectAsync
                }
                lastStamp = KoraEndpointsAggregator.modificationStamp(project)
                applySnapshot(snapshot)
            }
        }
    }

    fun schedulePoll() {
        if (project.isDisposed) return
        alarm.addRequest(
            {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(KORA_ENDPOINTS_TOOL_WINDOW_ID)
                if (toolWindow != null && toolWindow.isVisible) {
                    val stamp = KoraEndpointsAggregator.modificationStamp(project)
                    // Debounce: re-collect once the stamp has held still for a full tick, so a
                    // typing burst costs one collection after the pause, not one per tick.
                    if (stamp != lastStamp && stamp == seenStamp) refresh()
                    seenStamp = stamp
                }
                schedulePoll()
            },
            POLL_INTERVAL_MS,
        )
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        inFlight.set(false)
    }

    private fun applySnapshot(snapshot: KoraEndpointsSnapshot) {
        // Edits that don't change the result (e.g. inside method bodies) keep the tree and selection.
        if (project.isDisposed || snapshot == shown) return
        if (snapshot.isEmpty) {
            showStatus("No Kora endpoints, Kafka topics or entities found")
            return
        }

        val expandedPaths = collectExpandedPaths()
        val root = DefaultMutableTreeNode("Kora")

        val httpNode = DefaultMutableTreeNode(SectionPayload("HTTP Endpoints", snapshot.endpoints.size))
        root.add(httpNode)
        snapshot.endpoints.groupBy { ownerName(it) }.toSortedMap().forEach { (owner, endpoints) ->
            val ownerNode = DefaultMutableTreeNode(OwnerPayload(owner))
            httpNode.add(ownerNode)
            endpoints.forEach { endpoint -> ownerNode.add(DefaultMutableTreeNode(EndpointPayload(endpoint))) }
        }

        if (snapshot.consumers.isNotEmpty()) {
            val consumersNode = DefaultMutableTreeNode(SectionPayload("Kafka Consumers", snapshot.consumers.size))
            root.add(consumersNode)
            snapshot.consumers.forEach { consumer ->
                consumersNode.add(DefaultMutableTreeNode(ConsumerTopicPayload(consumer)))
            }
        }

        if (snapshot.publishers.isNotEmpty()) {
            val publishersNode = DefaultMutableTreeNode(
                SectionPayload("Kafka Producers", snapshot.publishers.sumOf { it.topics.size }),
            )
            root.add(publishersNode)
            snapshot.publishers.forEach { publisher ->
                val publisherNode = DefaultMutableTreeNode(PublisherPayload(publisher))
                publishersNode.add(publisherNode)
                publisher.topics.forEach { topic ->
                    publisherNode.add(DefaultMutableTreeNode(PublisherTopicPayload(topic)))
                }
            }
        }

        if (snapshot.entities.isNotEmpty()) {
            val entitiesNode = DefaultMutableTreeNode(SectionPayload("DB Entities", snapshot.entities.size))
            root.add(entitiesNode)
            snapshot.entities.forEach { entity ->
                entitiesNode.add(DefaultMutableTreeNode(EntityPayload(entity)))
            }
        }

        treeModel.setRoot(root)
        shown = snapshot
        restoreExpandedPaths(expandedPaths)
    }

    private fun ownerName(endpoint: KoraEndpoint): String {
        endpoint.targets.firstNotNullOfOrNull { it.classFqn }?.let { return it.substringAfterLast('.') }
        endpoint.targets.firstNotNullOfOrNull { it.specFileUrl }?.let { return it.substringAfterLast('/') }
        return "(unknown)"
    }

    private fun activateSelectedNode() {
        when (val payload = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is EndpointPayload -> EndpointNavigation.navigate(project, payload.endpoint.targets)
            is ConsumerTopicPayload -> EndpointNavigation.navigate(project, payload.consumer.targets)
            is PublisherTopicPayload -> EndpointNavigation.navigate(project, listOf(payload.topic.target))
            is EntityPayload -> EndpointNavigation.navigate(project, listOf(payload.entity.target))
        }
    }

    private fun showStatus(message: String) {
        shown = null
        treeModel.setRoot(
            DefaultMutableTreeNode("Kora").apply { add(DefaultMutableTreeNode(MessagePayload(message))) },
        )
    }

    private fun collectExpandedPaths(): Set<List<String>> {
        val result = HashSet<List<String>>()
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: continue
            if (tree.isExpanded(path)) result += path.path.map { it.toString() }
        }
        return result
    }

    private fun restoreExpandedPaths(saved: Set<List<String>>) {
        fun walk(node: TreeNode, trail: List<String>) {
            val currentTrail = trail + node.toString()
            if (node is DefaultMutableTreeNode && saved.contains(currentTrail)) {
                tree.expandPath(TreePath(node.path))
            }
            Collections.list(node.children() as java.util.Enumeration<TreeNode>).forEach { walk(it, currentTrail) }
        }
        (treeModel.root as? TreeNode)?.let { walk(it, emptyList()) }
    }

    private inner class RefreshAction : AnAction(AllIcons.Actions.Refresh) {
        init {
            templatePresentation.text = "Refresh"
            templatePresentation.description = "Reload Kora endpoints and Kafka topics"
        }

        override fun actionPerformed(e: AnActionEvent) {
            KoraEndpointsAggregator.invalidate()
            lastStamp = -1L
            refresh()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private class EndpointCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            hasFocus: Boolean,
            row: Int,
            hasChevron: Boolean,
        ) {
            when (val payload = (value as? DefaultMutableTreeNode)?.userObject) {
                is SectionPayload -> {
                    icon = when {
                        payload.title.startsWith("HTTP") -> AllIcons.General.Web
                        payload.title.startsWith("DB") -> AllIcons.Nodes.DataSchema
                        else -> AllIcons.Nodes.DataTables
                    }
                    append("${payload.title} (${payload.count})")
                }

                is OwnerPayload -> {
                    icon = AllIcons.Nodes.Class
                    append(payload.name)
                }

                is EndpointPayload -> {
                    icon = AllIcons.Nodes.Method
                    append(payload.endpoint.verb, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, verbColor(payload.endpoint.verb)), null)
                    append(" ")
                    append(payload.endpoint.path)
                    toolTipText = buildString {
                        payload.endpoint.summary?.let { append(it) }
                        payload.endpoint.operationId?.let {
                            if (isNotEmpty()) append("\n")
                            append("operationId: ").append(it)
                        }
                    }.takeIf { it.isNotEmpty() }
                }

                is ConsumerTopicPayload -> {
                    icon = AllIcons.Nodes.Method
                    append(payload.consumer.configPath)
                    toolTipText = "@KafkaListener config path (topics are configured there)"
                }

                is PublisherPayload -> {
                    icon = AllIcons.Nodes.Class
                    append(payload.publisher.publisherFqn.substringAfterLast('.'))
                    toolTipText = payload.publisher.configPath?.let { "Kafka producer, config: $it" } ?: "Kafka producer"
                }

                is PublisherTopicPayload -> {
                    icon = AllIcons.Nodes.Method
                    append(payload.topic.configPath)
                    toolTipText = "@KafkaPublisher.Topic config path → ${payload.topic.target.methodName}"
                }

                is EntityPayload -> {
                    icon = AllIcons.Nodes.Class
                    append(payload.entity.tableName)
                    toolTipText = payload.entity.classFqn
                }

                is MessagePayload -> {
                    icon = AllIcons.General.Information
                    append(payload.message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        private fun verbColor(verb: String): JBColor = when (verb) {
            "GET" -> JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            "POST" -> JBColor(Color(0xB3, 0x5C, 0x00), Color(0xFF, 0xB7, 0x4D))
            "PUT" -> JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
            "DELETE" -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x9A, 0x9A))
            "PATCH" -> JBColor(Color(0x6A, 0x1B, 0x9A), Color(0xCE, 0x93, 0xD8))
            else -> JBColor(Color(0x42, 0x42, 0x42), Color(0xB0, 0xB0, 0xB0))
        }
    }

    private sealed interface TreePayload

    // toString is the expanded-state key: no count in it, or a section collapses when its count changes.
    private data class SectionPayload(val title: String, val count: Int) : TreePayload {
        override fun toString() = title
    }

    private data class OwnerPayload(val name: String) : TreePayload {
        override fun toString() = name
    }

    private class EndpointPayload(val endpoint: KoraEndpoint) : TreePayload {
        override fun toString() = "${endpoint.verb} ${endpoint.path}"
    }

    private class ConsumerTopicPayload(val consumer: KafkaConsumer) : TreePayload {
        override fun toString() = consumer.configPath
    }

    private class PublisherPayload(val publisher: KafkaPublisher) : TreePayload {
        override fun toString() = publisher.publisherFqn.substringAfterLast('.')
    }

    private class PublisherTopicPayload(val topic: KafkaPublisherTopic) : TreePayload {
        override fun toString() = topic.configPath
    }

    private class EntityPayload(val entity: DbEntity) : TreePayload {
        override fun toString() = entity.tableName
    }

    private class MessagePayload(val message: String) : TreePayload {
        override fun toString() = message
    }

    companion object {
        const val KORA_ENDPOINTS_TOOL_WINDOW_ID = "Kora Structure"
        private const val POLL_INTERVAL_MS = 2000
    }
}
