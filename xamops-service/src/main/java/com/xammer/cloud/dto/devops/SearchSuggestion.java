package com.xammer.cloud.dto.devops;

/**
 * An autocomplete/search suggestion shown as the user types. The {@code type}
 * distinguishes the source of the suggestion (e.g. "script", "tag", "category").
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Autocomplete &lt; 150ms)
 */
public class SearchSuggestion {

    private String text;
    private String type;
    private double score;

    public SearchSuggestion() {}

    public SearchSuggestion(String text, String type) {
        this.text = text;
        this.type = type;
    }

    public SearchSuggestion(String text, String type, double score) {
        this.text = text;
        this.type = type;
        this.score = score;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}
