package ru.dsudomoin.koraplugin.config.datasource

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

class ImportKoraDataSourcesAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
            && DatabaseDataSourceCreator.isAvailable()
            && KoraLibraryUtil.hasKoraLibrary(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptors = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<KoraDataSourceDescriptor>, Exception>(
            { KoraDataSourceExtractor.findDataSources(project) },
            "Scanning Kora Config Files\u2026",
            true,
            project,
        )

        if (descriptors.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No data sources found in application.conf / application.yaml files.\n" +
                    "Make sure your config files contain sections with a 'jdbcUrl' key.",
                "Kora: Import Data Sources",
            )
            return
        }

        val dialog = ImportKoraDataSourcesDialog(project, descriptors)
        if (!dialog.showAndGet()) return

        val selected = dialog.getSelectedDescriptors()
        if (selected.isEmpty()) return

        val created = DatabaseDataSourceCreator.createDataSources(project, selected)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kora Plugin")
            .createNotification(
                "Kora: Data Sources Imported",
                "Successfully imported $created data source(s).",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
