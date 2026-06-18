package com.xammer.cloud.dto.cicd;

import java.util.List;

public class DiffSummaryDto {

    public static class CommitInfo {
        private String sha;
        private String message;
        private String author;
        private String timestamp;

        public String getSha() { return sha; }
        public void setSha(String sha) { this.sha = sha; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }

    private List<CommitInfo> commits;
    private int filesChanged;
    private List<String> changedFiles;
    private boolean noPreviousSuccess;

    public List<CommitInfo> getCommits() { return commits; }
    public void setCommits(List<CommitInfo> commits) { this.commits = commits; }
    public int getFilesChanged() { return filesChanged; }
    public void setFilesChanged(int filesChanged) { this.filesChanged = filesChanged; }
    public List<String> getChangedFiles() { return changedFiles; }
    public void setChangedFiles(List<String> changedFiles) { this.changedFiles = changedFiles; }
    public boolean isNoPreviousSuccess() { return noPreviousSuccess; }
    public void setNoPreviousSuccess(boolean noPreviousSuccess) { this.noPreviousSuccess = noPreviousSuccess; }
}
