package dev.codeduel.plugin;

import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.codeduel.shared.Protocol;

import java.io.IOException;

final class SolutionEditor {
    private final Project project;
    private VirtualFile file;
    private String currentRound;

    SolutionEditor(Project project) { this.project = project; }

    boolean open(Protocol.Room room, Protocol.Challenge challenge) throws IOException {
        if (!room.code().matches("[A-Z2-9]{8}") || room.roundId() == null
                || !room.roundId().matches("[a-f0-9-]{36}")) return false;
        if (!room.roundId().equals(currentRound) || file == null || !file.isValid()) {
            String path = "CodeDuel/" + room.code() + "/" + room.roundId() + "/Solution.java";
            var root = ScratchRootType.getInstance();
            file = ScratchFileService.getInstance().findFile(root, path, ScratchFileService.Option.existing_only);
            if (file == null) {
                file = root.createScratchFile(project, path, JavaLanguage.INSTANCE,
                        challenge.starter(), ScratchFileService.Option.create_if_missing);
            }
            currentRound = room.roundId();
        }
        return file != null && FileEditorManager.getInstance(project)
                .openTextEditor(new OpenFileDescriptor(project, file), true) != null;
    }

    String source(String round) {
        if (file == null || !file.isValid() || !round.equals(currentRound)) return null;
        var document = FileDocumentManager.getInstance().getDocument(file);
        return document == null ? null : document.getText();
    }
}
