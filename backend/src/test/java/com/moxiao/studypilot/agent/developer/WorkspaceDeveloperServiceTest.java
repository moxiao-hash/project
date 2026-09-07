package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceDeveloperServiceTest {

    private static final String OWNER_ID = "owner-1";
    private static final String WORKSPACE_ID = "workspace-1";

    @TempDir
    Path workspaceRoot;

    private WorkspaceDeveloperService service;

    @BeforeEach
    void setUp() throws Exception {
        ProjectWorkspaceJpaRepository workspaceRepository = mock(ProjectWorkspaceJpaRepository.class);
        ProjectWorkspaceEntity workspace = new ProjectWorkspaceEntity(
                WORKSPACE_ID, OWNER_ID, "test", workspaceRoot.toRealPath().toString(), "hash", Instant.now());
        when(workspaceRepository.findByIdAndOwnerId(WORKSPACE_ID, OWNER_ID))
                .thenReturn(Optional.of(workspace));
        service = new WorkspaceDeveloperService(workspaceRepository);
    }

    @Test
    void refusesSensitiveFilesAndSymbolicLinkEscapes() throws Exception {
        Files.writeString(workspaceRoot.resolve(".env"), "DEEPSEEK_API_KEY=secret");
        Path outside = Files.createTempFile("studypilot-secret", ".txt");
        Files.writeString(outside, "outside-secret");
        Files.createSymbolicLink(workspaceRoot.resolve("linked-secret.txt"), outside);

        assertThrows(IllegalArgumentException.class,
                () -> service.readFile(OWNER_ID, WORKSPACE_ID, ".env"));
        assertThrows(IllegalArgumentException.class,
                () -> service.readFile(OWNER_ID, WORKSPACE_ID, "linked-secret.txt"));
    }

    @Test
    void gitStatusReflectsTheRepositoryInsteadOfReturningConstants() throws Exception {
        runGit("init", "-b", "main");
        Files.writeString(workspaceRoot.resolve("tracked.txt"), "first\n");
        runGit("add", "tracked.txt");
        runGit("-c", "user.name=StudyPilot", "-c", "user.email=test@example.com",
                "commit", "-m", "initial");
        Files.writeString(workspaceRoot.resolve("tracked.txt"), "changed\n");
        Files.writeString(workspaceRoot.resolve("new.txt"), "new\n");

        WorkspaceGitStatusResponse status = service.getGitStatus(OWNER_ID, WORKSPACE_ID);

        assertFalse(status.clean());
        assertTrue(status.modifiedFiles().contains("tracked.txt"));
        assertTrue(status.untrackedFiles().contains("new.txt"));
        assertTrue(status.currentCommit().matches("[0-9a-f]{40}"));
    }

    @Test
    void patchPreviewRejectsHunksThatDoNotMatchCurrentContent() throws Exception {
        Files.writeString(workspaceRoot.resolve("Example.java"), "class Example {\n}\n");
        String invalid = """
                --- a/Example.java
                +++ b/Example.java
                @@ -1,2 +1,2 @@
                -class Missing {
                +class Changed {
                 }
                """;

        CodePatchPreview preview = service.previewPatch(
                OWNER_ID, WORKSPACE_ID, "Example.java", invalid);

        assertTrue(preview.conflict());
        assertFalse(preview.safeToApply());
    }

    @Test
    void patchPreviewRejectsOversizedInputBeforeParsingIt() throws Exception {
        Files.writeString(workspaceRoot.resolve("Example.java"), "class Example {\n}\n");

        CodePatchPreview preview = service.previewPatch(
                OWNER_ID, WORKSPACE_ID, "Example.java", "x".repeat(20_001));

        assertTrue(preview.conflict());
        assertEquals("补丁内容不能超过 20000 字符", preview.conflictReason());
    }

    @Test
    void appliesOnlyThePreviewedFileVersion() throws Exception {
        Path source = workspaceRoot.resolve("Example.java");
        Files.writeString(source, "class Example {\n}\n");
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(source);
        String diff = """
                --- a/Example.java
                +++ b/Example.java
                @@ -1,2 +1,3 @@
                 class Example {
                +    int value = 1;
                 }
                """;
        CodePatchPreview preview = service.previewPatch(
                OWNER_ID, WORKSPACE_ID, "Example.java", diff);

        assertTrue(preview.safeToApply());
        assertNotNull(preview.expectedSha256());
        assertNotEquals(preview.expectedSha256(), preview.resultSha256());

        ApplyCodePatchResult result = service.applyConfirmedPatch(OWNER_ID,
                new ApplyCodePatchRequest(WORKSPACE_ID, "Example.java", diff,
                        preview.expectedSha256(), "增加字段"));

        assertTrue(result.applied());
        assertEquals("class Example {\n    int value = 1;\n}\n", Files.readString(source));
        assertEquals(preview.resultSha256(), result.resultSha256());
        assertEquals(originalPermissions, Files.getPosixFilePermissions(source));

        Files.writeString(source, "class Example {\n    int value = 2;\n}\n");
        assertThrows(com.moxiao.studypilot.shared.error.ConflictException.class,
                () -> service.applyConfirmedPatch(OWNER_ID,
                        new ApplyCodePatchRequest(WORKSPACE_ID, "Example.java", diff,
                                preview.expectedSha256(), "重复应用旧预览")));
        assertEquals("class Example {\n    int value = 2;\n}\n", Files.readString(source));
    }

    @Test
    void redactsCredentialsFromReadableDeveloperOutputs() throws Exception {
        runGit("init", "-b", "main");
        Path source = workspaceRoot.resolve("Example.java");
        Files.writeString(source, "class Example {\n}\n");
        runGit("add", "Example.java");
        runGit("-c", "user.name=StudyPilot", "-c", "user.email=test@example.com",
                "commit", "-m", "initial");
        String credential = "sk-1234567890abcdefghijklmnop";
        Files.writeString(source, "class Example {\n    String apiKey = \"" + credential + "\";\n}\n");

        WorkspaceFileReadResponse read = service.readFile(
                OWNER_ID, WORKSPACE_ID, "Example.java");
        WorkspaceSearchResponse search = service.searchCode(
                OWNER_ID, WORKSPACE_ID, "apiKey");
        WorkspaceGitTextResponse diff = service.getGitDiff(OWNER_ID, WORKSPACE_ID);
        WorkspaceGitTextResponse log = service.getGitLog(OWNER_ID, WORKSPACE_ID, 20);
        CodePatchPreview sensitivePreview = service.previewPatch(
                OWNER_ID, WORKSPACE_ID, "Example.java", """
                        --- a/Example.java
                        +++ b/Example.java
                        @@ -1,3 +1,3 @@
                         class Example {
                        -    String apiKey = "sk-1234567890abcdefghijklmnop";
                        +    String apiKey = "sk-abcdefghijklmnopqrstuvwxyz";
                         }
                        """);

        assertFalse(read.content().contains(credential));
        assertTrue(read.content().contains("[REDACTED]"));
        assertFalse(search.matches().get(0).lineContent().contains(credential));
        assertFalse(diff.content().contains(credential));
        assertTrue(log.content().contains("initial"));
        assertTrue(sensitivePreview.conflict());
        assertNull(sensitivePreview.unifiedDiff());
    }

    private void runGit(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = workspaceRoot.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(new String(process.getInputStream().readAllBytes()));
        }
    }
}
