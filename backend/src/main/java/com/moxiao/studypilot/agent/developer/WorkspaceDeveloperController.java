package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/developer")
public class WorkspaceDeveloperController {

    private final WorkspaceDeveloperService developerService;

    public WorkspaceDeveloperController(WorkspaceDeveloperService developerService) {
        this.developerService = developerService;
    }

    @GetMapping("/workspaces/{workspaceId}/tree")
    public WorkspaceFileTreeResponse getTree(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId
    ) {
        return developerService.getFileTree(user.id(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/file")
    public WorkspaceFileReadResponse readFile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId,
            @RequestParam String path
    ) {
        return developerService.readFile(user.id(), workspaceId, path);
    }

    @GetMapping("/workspaces/{workspaceId}/search")
    public WorkspaceSearchResponse search(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId,
            @RequestParam String q
    ) {
        return developerService.searchCode(user.id(), workspaceId, q);
    }

    @GetMapping("/workspaces/{workspaceId}/git-status")
    public WorkspaceGitStatusResponse getGitStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId
    ) {
        return developerService.getGitStatus(user.id(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/git-diff")
    public WorkspaceGitTextResponse getGitDiff(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId
    ) {
        return developerService.getGitDiff(user.id(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/git-log")
    public WorkspaceGitTextResponse getGitLog(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return developerService.getGitLog(user.id(), workspaceId, limit);
    }

    @PostMapping("/workspaces/{workspaceId}/patch-preview")
    public CodePatchPreview previewPatch(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId,
            @RequestParam String targetFile,
            @RequestBody String diff
    ) {
        return developerService.previewPatch(user.id(), workspaceId, targetFile, diff);
    }

    @PostMapping("/workspaces/{workspaceId}/test-recommendations")
    public DeveloperTestRecommendation recommendTests(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId,
            @RequestBody java.util.List<String> changedFiles
    ) {
        return developerService.recommendTests(user.id(), workspaceId, changedFiles);
    }

    @PostMapping("/workspaces/{workspaceId}/git-commit-preview")
    public GitCommitPreview previewGitCommit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId,
            @RequestBody CreateGitCommitPreviewRequest request
    ) {
        return developerService.previewGitCommit(
                user.id(), workspaceId, request.paths(), request.message());
    }

    @GetMapping("/workspaces/{workspaceId}/git-push-preview")
    public GitPushPreview previewGitPush(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String workspaceId
    ) {
        return developerService.previewGitPush(user.id(), workspaceId);
    }

}
