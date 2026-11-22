package dev.codeduel.plugin;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.codeduel.shared.Catalog;
import dev.codeduel.shared.Protocol;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class SolutionEditorPlatformTest extends BasePlatformTestCase {
    public void testOpensJavaScratchAndPreservesTheEditedDraft() throws Exception {
        var project = getProject();
        var challenge = Catalog.all().getFirst();
        String roundId = UUID.randomUUID().toString();
        var room = new Protocol.Room("ABCD2345", Protocol.Phase.ACTIVE, List.of(), challenge.id(), roundId, 0, 0, null);
        var editor = new SolutionEditor(project);
        assertTrue(editor.open(room, challenge));
        assertEquals(challenge.starter(), editor.source(roundId));

        var file = Arrays.stream(FileEditorManager.getInstance(project).getOpenFiles())
                .filter(candidate -> candidate.getPath().contains(roundId)).findFirst().orElseThrow();
        assertEquals("Solution.java", file.getName());
        String draft = "public class Solution { public static int solve(int[] a) { return 0; } }";
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var document = FileDocumentManager.getInstance().getDocument(file);
            assertNotNull(document);
            document.setText(draft);
        });
        var reopened = new SolutionEditor(project);
        assertTrue(reopened.open(room, challenge));
        assertEquals(draft, reopened.source(roundId));
    }
}
