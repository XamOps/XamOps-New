package com.xammer.cloud.dto.devops;

/**
 * Aggregated engagement metrics for a single DevOps script. Surfaced on script
 * cards / detail views and used to rank popular scripts.
 *
 * Requirements: REQ-2.4.3 (Usage Tracking), REQ-2.9.1 (Usage Analytics)
 */
public class ScriptAnalytics {

    private Long scriptId;
    private String scriptName;
    private int viewCount;
    private int downloadCount;
    private int copyCount;
    private int favoriteCount;
    private double averageRating;
    private int ratingCount;

    public ScriptAnalytics() {}

    public ScriptAnalytics(Long scriptId, String scriptName, int viewCount, int downloadCount,
                           int copyCount, int favoriteCount, double averageRating, int ratingCount) {
        this.scriptId = scriptId;
        this.scriptName = scriptName;
        this.viewCount = viewCount;
        this.downloadCount = downloadCount;
        this.copyCount = copyCount;
        this.favoriteCount = favoriteCount;
        this.averageRating = averageRating;
        this.ratingCount = ratingCount;
    }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getScriptName() { return scriptName; }
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public int getDownloadCount() { return downloadCount; }
    public void setDownloadCount(int downloadCount) { this.downloadCount = downloadCount; }

    public int getCopyCount() { return copyCount; }
    public void setCopyCount(int copyCount) { this.copyCount = copyCount; }

    public int getFavoriteCount() { return favoriteCount; }
    public void setFavoriteCount(int favoriteCount) { this.favoriteCount = favoriteCount; }

    public double getAverageRating() { return averageRating; }
    public void setAverageRating(double averageRating) { this.averageRating = averageRating; }

    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
}
