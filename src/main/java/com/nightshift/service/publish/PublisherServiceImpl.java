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
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

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
                : "owner/target-repo";
        String baseBranch = props.getGit() != null && !props.getGit().getBaseBranch().isBlank()
                ? props.getGit().getBaseBranch()
                : "main";

        if (pullRequestRepository.existsByRepoFullNameAndBranchName(repoFullName, branchName)) {
            throw new BadResourceRequestException(ErrorCodes.PR_ALREADY_OPEN,
                    "Pull request already exists for branch: " + branchName);
        }

        String scope = incident.getServiceName() != null ? incident.getServiceName() : "core";
        String commitTitle = "fix(" + scope + "): " + incident.getTitle();
        String prBody = prBodyRenderer.render(incident, proposal, scanRun);

        String inputSummary = "repo=" + repoFullName + " branch=" + branchName;

        boolean dryRun = props.getPublish() != null && props.getPublish().isDryRun();
        String githubToken = System.getenv("GITHUB_TOKEN");

        int prNumber;
        String prUrl;

        if (dryRun || githubToken == null || githubToken.isBlank()) {
            log.info("[DRY-RUN] Creating PR for incident {}: repo={} branch={} base={}",
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
                .provider("GITHUB")
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
        String wsPath = props.getWorkspace() != null ? props.getWorkspace() : "./workspace";
        File repoDir = new File(wsPath);

        try (Git git = Git.open(repoDir)) {
            // Checkout base branch
            git.checkout().setName(baseBranch).call();

            // Create new branch
            git.checkout().setCreateBranch(true).setName(branchName).call();

            // Apply patch
            git.apply().setPatch(new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8))).call();

            // Commit
            String commitMsg = title + "\n\nRefs: nightshift/" + fingerprint;
            git.commit()
                    .setMessage(commitMsg)
                    .setAuthor("Nightshift Bot", "nightshift@example.com")
                    .call();

            // Push branch
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
                    .setRemote("origin")
                    .call();
        }
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
        if (response.statusCode() != 201) {
            throw new IllegalStateException("GitHub API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode respNode = MAPPER.readTree(response.body());
        int number = respNode.path("number").asInt(1);
        String url = respNode.path("html_url").asText("https://github.com/" + repo + "/pull/" + number);
        return new PrApiResult(number, url);
    }

    private record PrApiResult(int number, String url) {}
}