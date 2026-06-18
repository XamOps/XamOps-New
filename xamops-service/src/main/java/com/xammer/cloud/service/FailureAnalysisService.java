package com.xammer.cloud.service;

import com.xammer.cloud.dto.cicd.DiffSummaryDto;
import com.xammer.cloud.dto.cicd.FailureAnalysisDto;
import com.xammer.cloud.repository.CicdRunRecordRepository;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class FailureAnalysisService {

    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("Exception:|Caused by:|at com\\.|at org\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("BUILD FAILURE|Tests run:.*Failures:[^0]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("npm ERR!|AssertionError", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Error:|FAILED|exit code [^0]", Pattern.CASE_INSENSITIVE)
    );
    private static final int CONTEXT = 2;
    private static final int MAX_SNIPPETS = 3;
    private static final int FALLBACK_LINES = 20;

    private final CicdRunRecordRepository runRecordRepo;
    private final CicdStatusService cicdStatusService;
    private final JenkinsService jenkinsService;
    private final org.springframework.web.client.RestTemplate restTemplate;

    public FailureAnalysisService(CicdRunRecordRepository runRecordRepo,
                                   CicdStatusService cicdStatusService,
                                   JenkinsService jenkinsService,
                                   RestTemplateBuilder restTemplateBuilder) {
        this.runRecordRepo = runRecordRepo;
        this.cicdStatusService = cicdStatusService;
        this.jenkinsService = jenkinsService;
        this.restTemplate = restTemplateBuilder != null ? restTemplateBuilder.build() : null;
    }

    public FailureAnalysisDto parseLog(String rawLog) {
        FailureAnalysisDto dto = new FailureAnalysisDto();
        if (rawLog == null || rawLog.isBlank()) {
            dto.setSnippets(List.of());
            dto.setFallback(true);
            return dto;
        }

        String[] lines = rawLog.split("\n", -1);
        List<FailureAnalysisDto.Snippet> snippets = new ArrayList<>();
        Set<Integer> usedLines = new HashSet<>();

        outer:
        for (Pattern pattern : PATTERNS) {
            for (int i = 0; i < lines.length; i++) {
                if (snippets.size() >= MAX_SNIPPETS) break outer;
                if (pattern.matcher(lines[i]).find() && !usedLines.contains(i)) {
                    int from = Math.max(0, i - CONTEXT);
                    int to = Math.min(lines.length - 1, i + CONTEXT);
                    List<String> ctx = new ArrayList<>();
                    for (int j = from; j <= to; j++) {
                        ctx.add(lines[j]);
                        usedLines.add(j);
                    }
                    FailureAnalysisDto.Snippet s = new FailureAnalysisDto.Snippet();
                    s.setMatchLine(i - from);
                    s.setLines(ctx);
                    snippets.add(s);
                }
            }
        }

        if (snippets.isEmpty()) {
            dto.setFallback(true);
            // Trim trailing empty lines produced by split on logs ending with \n
            int end = lines.length;
            while (end > 0 && lines[end - 1].isEmpty()) end--;
            int from = Math.max(0, end - FALLBACK_LINES);
            FailureAnalysisDto.Snippet s = new FailureAnalysisDto.Snippet();
            s.setMatchLine(0);
            s.setLines(Arrays.asList(Arrays.copyOfRange(lines, from, end)));
            dto.setSnippets(List.of(s));
        } else {
            dto.setFallback(false);
            dto.setSnippets(snippets);
        }
        return dto;
    }

    public DiffSummaryDto getDiffSummaryForGitHub(Long userId, String workflowName,
                                                   String owner, String repo,
                                                   String headSha, String pat) {
        DiffSummaryDto dto = new DiffSummaryDto();

        List<com.xammer.cloud.domain.CicdRunRecord> successRuns =
                runRecordRepo.findTopSuccessRun(userId, workflowName, "GITHUB", PageRequest.of(0, 1));

        if (successRuns.isEmpty()) {
            dto.setNoPreviousSuccess(true);
            dto.setCommits(List.of());
            dto.setChangedFiles(List.of());
            return dto;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + pat);
            headers.set("Accept", "application/vnd.github.v3+json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String baseRunId = successRuns.get(0).getRunId();
            String runUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + baseRunId;
            var runResp = restTemplate.exchange(runUrl, HttpMethod.GET, entity, com.fasterxml.jackson.databind.JsonNode.class);
            String baseSha = runResp.getBody() != null ? runResp.getBody().path("head_sha").asText() : null;

            if (baseSha == null || baseSha.isBlank()) {
                dto.setNoPreviousSuccess(true);
                dto.setCommits(List.of());
                dto.setChangedFiles(List.of());
                return dto;
            }

            String compareUrl = "https://api.github.com/repos/" + owner + "/" + repo
                    + "/compare/" + baseSha + "..." + headSha;
            var compareResp = restTemplate.exchange(compareUrl, HttpMethod.GET, entity, com.fasterxml.jackson.databind.JsonNode.class);
            var body = compareResp.getBody();

            List<DiffSummaryDto.CommitInfo> commits = new ArrayList<>();
            if (body != null && body.has("commits")) {
                for (var c : body.get("commits")) {
                    DiffSummaryDto.CommitInfo ci = new DiffSummaryDto.CommitInfo();
                    ci.setSha(c.path("sha").asText("").substring(0, Math.min(7, c.path("sha").asText("").length())));
                    ci.setMessage(c.path("commit").path("message").asText("").split("\n")[0]);
                    ci.setAuthor(c.path("commit").path("author").path("name").asText(""));
                    ci.setTimestamp(c.path("commit").path("author").path("date").asText(""));
                    commits.add(ci);
                }
            }

            List<String> files = new ArrayList<>();
            int fileCount = 0;
            if (body != null && body.has("files")) {
                fileCount = body.get("files").size();
                for (var f : body.get("files")) {
                    files.add(f.path("filename").asText(""));
                }
            }

            dto.setCommits(commits);
            dto.setFilesChanged(fileCount);
            dto.setChangedFiles(files);
            dto.setNoPreviousSuccess(false);
        } catch (Exception e) {
            dto.setNoPreviousSuccess(true);
            dto.setCommits(List.of());
            dto.setChangedFiles(List.of());
        }
        return dto;
    }
}
