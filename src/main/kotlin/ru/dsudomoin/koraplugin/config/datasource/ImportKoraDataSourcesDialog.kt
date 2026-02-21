package ru.dsudomoin.koraplugin.config.datasource

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.BooleanTableCellEditor
import com.intellij.ui.BooleanTableCellRenderer
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.AbstractTableModel

class ImportKoraDataSourcesDialog(
    project: Project,
    private val descriptors: List<KoraDataSourceDescriptor>,
) : DialogWrapper(project) {

    private val selected = BooleanArray(descriptors.size) { true }

    init {
        title = "Import Kora Data Sources"
        setOKButtonText("Import")
        init()
    }

    fun getSelectedDescriptors(): List<KoraDataSourceDescriptor> {
        return descriptors.filterIndexed { i, _ -> selected[i] }
    }

    override fun createCenterPanel(): JComponent {
        val model = object : AbstractTableModel() {
            private val columns = arrayOf("", "Name", "JDBC URL", "Username", "Driver", "File")

            override fun getRowCount() = descriptors.size
            override fun getColumnCount() = columns.size
            override fun getColumnName(col: Int) = columns[col]

            override fun getValueAt(row: Int, col: Int): Any? = when (col) {
                0 -> selected[row]
                1 -> descriptors[row].name
                2 -> descriptors[row].jdbcUrl
                3 -> descriptors[row].username ?: ""
                4 -> descriptors[row].driverType ?: "unknown"
                5 -> descriptors[row].sourceFile.name
                else -> null
            }

            override fun isCellEditable(row: Int, col: Int) = col == 0

            override fun setValueAt(value: Any?, row: Int, col: Int) {
                if (col == 0 && value is Boolean) {
                    selected[row] = value
                    fireTableCellUpdated(row, col)
                }
            }

            override fun getColumnClass(col: Int): Class<*> = when (col) {
                0 -> Boolean::class.javaObjectType
                else -> String::class.java
            }
        }

        val table = JBTable(model).apply {
            columnModel.getColumn(0).apply {
                maxWidth = 30
                cellRenderer = BooleanTableCellRenderer()
                cellEditor = BooleanTableCellEditor()
            }
            columnModel.getColumn(1).preferredWidth = 120
            columnModel.getColumn(2).preferredWidth = 300
            columnModel.getColumn(3).preferredWidth = 80
            columnModel.getColumn(4).preferredWidth = 80
            columnModel.getColumn(5).preferredWidth = 120
        }

        val panel = JPanel(BorderLayout())
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        panel.preferredSize = Dimension(750, 300)
        return panel
    }
}
