package com.xammer.cloud.dto.cicd;

import java.util.List;

public class FailureAnalysisDto {

    public static class Snippet {
        private int matchLine;
        private List<String> lines;

        public int getMatchLine() { return matchLine; }
        public void setMatchLine(int matchLine) { this.matchLine = matchLine; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> lines) { this.lines = lines; }
    }

    private List<Snippet> snippets;
    private boolean fallback;

    public List<Snippet> getSnippets() { return snippets; }
    public void setSnippets(List<Snippet> snippets) { this.snippets = snippets; }
    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
}
