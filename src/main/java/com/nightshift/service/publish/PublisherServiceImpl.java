package com.nightshift.service.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.BadResourceRequestException;
import com.nightshift.exception.ExternalServiceException;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.PullRequest;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.model.enums.PrState;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.PatchProposalRepository;
import com.nightshift.repository.PullRequestRepository;
import com.nightshift.util.AgentStepRecorder;
import com.nightshift.util.PrBodyRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Default implementation of {@link PublisherService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublisherServiceImpl implements PublisherService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NightshiftProperties props;
    private final PullRequestRepository pullRequestRepository;
    private final PatchProposalRepository patchProposalRepository;
    private final IncidentRepository incidentRepository;
    private final PrBodyRenderer prBodyRenderer;
    private final AgentStepRecorder recorder;
    private final com.nightshift.service.outbox.NotificationService notificationService;

    @Override
    @Transactional
    public PullRequest publish(Incident incident, PatchProposal proposal) {
        return publish(incident, proposal, null);
    }

    @Override
    @Transactional
    public PullRequest publish(Incident incident, PatchProposal proposal, ScanRun scanRun) {
        long start = System.currentTimeMillis();

        String shortFp = incident.getFingerprint().length() > 8
                ? incident.getFingerprint().substring(0, 8)
                : incident.getFingerprint();
        String severity = incident.getSeverity() != null
                ? incident.getSeverity().name().toLowerCase()
                : "incident";
        String branchName = "nightshift/fix-" + severity + "-" + shortFp;

        String repoFullName = props.getGit() != null && !props.getGit().getRepo().isBlank()
                ? props.getGit().getRepo()
                : "MdTanvirHossainTusher/nightshift";
        String baseBranch = props.getGit() != null && !props.getGit().getBaseBranch().isBlank()
                ? props.getGit().getBaseBranch()
                : "main";

        boolean dryRun = props.getPublish() != null && props.getPublish().isDryRun();
        String githubToken = System.getProperty("GITHUB_TOKEN");
        if (githubToken == null || githubToken.isBlank()) {
            githubToken = System.getenv("GITHUB_TOKEN");
        }
        boolean isDryRun = dryRun || githubToken == null || githubToken.isBlank();

        String scope = incident.getServiceName() != null ? incident.getServiceName() : "core";
        String commitTitle = "fix(" + scope + "): " + incident.getTitle();
        String prBody = prBodyRenderer.render(incident, proposal, scanRun);
        String inputSummary = "repo=" + repoFullName + " branch=" + branchName;

        java.util.Optional<PullRequest> existingOpt = pullRequestRepository.findByRepoFullNameAndBranchName(repoFullName, branchName);
        if (existingOpt.isPresent()) {
            PullRequest existing = existingOpt.get();
            if ("SIMULATED".equalsIgnoreCase(existing.getProvider()) && !isDryRun) {
                log.info("Promoting simulated PR for branch {} to live GitHub PR...", branchName);
                try {
                    // Publish the proposal the simulated PR was created from, not whatever
                    // proposal is newest for the incident (that may be a rejected revision).
                    PatchProposal published = existing.getPatchProposal() != null ? existing.getPatchProposal() : proposal;
                    executeGitPublish(branchName, baseBranch, published.getUnifiedDiff(), commitTitle, incident.getFingerprint(), githubToken);
                    PrApiResult result = openGitHubPullRequest(repoFullName, commitTitle,
                            prBodyRenderer.render(incident, published, scanRun), branchName, baseBranch, githubToken);
                    existing.setProvider("GITHUB");
                    existing.setPrNumber(result.number());
                    existing.setPrUrl(result.url());
                    existing.setState(PrState.OPEN);
                    existing.setOpenedAt(Instant.now());
                    existing = pullRequestRepository.save(existing);
                    notificationService.createNotificationAndOutbox(existing);
                    return existing;
                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - start;
                    recorder.recordToolCall(scanRun, incident, AgentRole.PUBLISH, 0,
                            "publisher", inputSummary, "FAILED: " + e.getMessage(), latency, e);
                    throw new ExternalServiceException(ErrorCodes.UPSTREAM_SERVICE_UNAVAILABLE,
                            "Failed to publish PR to GitHub: " + e.getMessage());
                }
            }
            throw new BadResourceRequestException(ErrorCodes.PR_ALREADY_OPEN,
                    "Pull request already exists for branch: " + branchName);
        }

        int prNumber;
        String prUrl;

        if (isDryRun) {
            log.info("[DRY-RUN] Creating simulated PR for incident {}: repo={} branch={} base={}",
                    incident.getFingerprint(), repoFullName, branchName, baseBranch);
            prNumber = Math.abs((incident.getFingerprint() + branchName).hashCode() % 9000) + 100;
            prUrl = "https://github.com/" + repoFullName + "/pull/" + prNumber;
        } else {
            // Live execution via JGit and GitHub REST API
            try {
                executeGitPublish(branchName, baseBranch, proposal.getUnifiedDiff(), commitTitle, incident.getFingerprint(), githubToken);
                PrApiResult result = openGitHubPullRequest(repoFullName, commitTitle, prBody, branchName, baseBranch, githubToken);
                prNumber = result.number();
                prUrl = result.url();
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                recorder.recordToolCall(scanRun, incident, AgentRole.PUBLISH, 0,
                        "publisher", inputSummary, "FAILED: " + e.getMessage(), latency, e);
                throw new ExternalServiceException(ErrorCodes.UPSTREAM_SERVICE_UNAVAILABLE,
                        "Failed to publish PR to GitHub: " + e.getMessage());
            }
        }

        PullRequest pr = PullRequest.builder()
                .incident(incident)
                .patchProposal(proposal)
                .provider(isDryRun ? "SIMULATED" : "GITHUB")
                .repoFullName(repoFullName)
                .branchName(branchName)
                .baseBranch(baseBranch)
                .prNumber(prNumber)
                .prUrl(prUrl)
                .state(PrState.OPEN)
                .assigneeHandle(incident.getAssigneeHandle())
                .openedAt(Instant.now())
                .build();

        pr = pullRequestRepository.save(pr);
        notificationService.createNotificationAndOutbox(pr);

        proposal.setStatus(PatchStatus.PUBLISHED);
        patchProposalRepository.save(proposal);

        incident.setStatus(IncidentStatus.PR_OPEN);
        incidentRepository.save(incident);

        long latency = System.currentTimeMillis() - start;
        String outputSummary = "PR #" + prNumber + " opened at " + prUrl;
        recorder.recordToolCall(scanRun, incident, AgentRole.PUBLISH, 0,
                "publisher", inputSummary, outputSummary, latency, null);

        log.info("Pull request successfully published: id={} url={}", pr.getId(), prUrl);
        return pr;
    }

    // ── Internal Git & GitHub helpers ─────────────────────────────────────────

    private void executeGitPublish(String branchName, String baseBranch, String diff,
                                   String title, String fingerprint, String token) throws Exception {
        String repoFullName = props.getGit() != null && !props.getGit().getRepo().isBlank()
                ? props.getGit().getRepo()
                : "MdTanvirHossainTusher/nightshift";
        String remoteUrl = "https://github.com/" + repoFullName + ".git";
        UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider("x-access-token", token);

        // A dedicated clone of the real repo. The workspace root also holds the locator's
        // target-repo mount, which is not a git checkout of the PR repository.
        String wsPath = props.getWorkspace() != null ? props.getWorkspace() : "./workspace";
        File repoDir = new File(new File(wsPath, "publish"), repoFullName.replace('/', '_'));

        Git git;
        if (new File(repoDir, ".git").exists()) {
            git = Git.open(repoDir);
            git.fetch().setRemote("origin").setCredentialsProvider(credentials).call();
        } else {
            log.info("Cloning {} into {} for publishing...", repoFullName, repoDir.getAbsolutePath());
            repoDir.mkdirs();
            git = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(repoDir)
                    .setCredentialsProvider(credentials)
                    .setBranch(baseBranch)
                    .call();
        }

        try (git) {
            // Start every PR branch from the current remote base, discarding leftovers of a
            // previous attempt on the same branch.
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
            git.clean().setCleanDirectories(true).setForce(true).call();
            git.checkout()
                    .setName(branchName)
                    .setCreateBranch(true)
                    .setForced(true)
                    .setStartPoint("origin/" + baseBranch)
                    .call();

            String repoDiff = rebaseDiffPaths(diff, repoDir.toPath());
            git.apply().setPatch(new ByteArrayInputStream(repoDiff.getBytes(StandardCharsets.UTF_8))).call();

            if (git.status().call().isClean()) {
                throw new IllegalStateException("Patch applied but produced no changes in " + repoFullName);
            }

            git.add().addFilepattern(".").call();
            String commitMsg = title + "\n\nRefs: nightshift/" + fingerprint;
            git.commit()
                    .setMessage(commitMsg)
                    .setAuthor("Nightshift Bot", "nightshift@example.com")
                    .setCommitter("Nightshift Bot", "nightshift@example.com")
                    .call();

            // Bot-owned branch: force so a re-publish replaces an earlier attempt.
            git.push()
                    .setCredentialsProvider(credentials)
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+refs/heads/" + branchName + ":refs/heads/" + branchName))
                    .call();
        }
    }

    /**
     * Diffs are computed against the locator's target repo, whose root may be a sub-directory of
     * the PR repository (the demo lives at {@code demo/target-repo/} in this repo). Rewrites each
     * file header to the path that actually exists in the clone.
     */
    private String rebaseDiffPaths(String diff, Path cloneRoot) throws IOException {
        Map<String, String> remap = new HashMap<>();
        for (String line : diff.lines().toList()) {
            if (!line.startsWith("+++ b/")) continue;
            String rel = line.substring("+++ b/".length()).strip();
            if (remap.containsKey(rel) || Files.exists(cloneRoot.resolve(rel))) continue;

            List<Path> matches;
            try (Stream<Path> walk = Files.walk(cloneRoot)) {
                matches = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> !cloneRoot.relativize(p).toString().replace('\\', '/').startsWith(".git/"))
                        .filter(p -> p.toString().replace('\\', '/').endsWith("/" + rel))
                        .toList();
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("Cannot map " + rel + " into the repository ("
                        + matches.size() + " candidate files)");
            }
            remap.put(rel, cloneRoot.relativize(matches.get(0)).toString().replace('\\', '/'));
        }
        if (remap.isEmpty()) return diff;

        StringBuilder out = new StringBuilder(diff.length() + 64);
        for (String line : diff.lines().toList()) {
            if (line.startsWith("--- a/") && remap.containsKey(line.substring(6).strip())) {
                line = "--- a/" + remap.get(line.substring(6).strip());
            } else if (line.startsWith("+++ b/") && remap.containsKey(line.substring(6).strip())) {
                line = "+++ b/" + remap.get(line.substring(6).strip());
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private PrApiResult openGitHubPullRequest(String repo, String title, String body,
                                              String head, String base, String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String jsonPayload = MAPPER.writeValueAsString(Map.of(
                "title", title,
                "body", body,
                "head", head,
                "base", base
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repo + "/pulls"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 422 && response.body().contains("already exists")) {
            // The branch already has an open PR from an earlier publish; the force-push updated it.
            return findOpenPullRequest(client, repo, head, token);
        }
        if (response.statusCode() != 201) {
            throw new IllegalStateException("GitHub API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode respNode = MAPPER.readTree(response.body());
        int number = respNode.path("number").asInt(1);
        String url = respNode.path("html_url").asText("https://github.com/" + repo + "/pull/" + number);
        return new PrApiResult(number, url);
    }

    private PrApiResult findOpenPullRequest(HttpClient client, String repo, String head, String token) throws Exception {
        String owner = repo.substring(0, repo.indexOf('/'));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repo + "/pulls?state=open&head="
                        + owner + ":" + URLEncoder.encode(head, StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode prs = MAPPER.readTree(response.body());
        if (response.statusCode() != 200 || !prs.isArray() || prs.isEmpty()) {
            throw new IllegalStateException("GitHub reports an existing PR for " + head + " but it could not be found");
        }
        return new PrApiResult(prs.get(0).path("number").asInt(), prs.get(0).path("html_url").asText());
    }

    private record PrApiResult(int number, String url) {}
}
