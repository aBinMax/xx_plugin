import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = ToolWindow(project)
        val content = ContentFactory
            .getInstance()
            .createContent(
                myToolWindow.getContent(),
                null,
                false
            )
        toolWindow.contentManager.addContent(content)
    }
}