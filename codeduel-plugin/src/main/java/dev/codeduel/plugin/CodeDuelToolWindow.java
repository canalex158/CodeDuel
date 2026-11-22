package dev.codeduel.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class CodeDuelToolWindow implements ToolWindowFactory {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var panel = new DuelPanel(project);
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
