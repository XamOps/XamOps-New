package com.xammer.cloud.dto.cicd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JenkinsJobDto {
    private String name;
    private String url;
    private String color; // blue, red, yellow, blue_anime, etc.

    @JsonProperty("lastBuild")
    private JenkinsBuild lastBuild;

    // Derived status for UI
    public String getDisplayStatus() {
        if (color == null)
            return "Unknown";
        if (color.endsWith("_anime"))
            return "Running";
        if (color.startsWith("blue"))
            return "Success";
        if (color.startsWith("red"))
            return "Failure";
        if (color.startsWith("yellow"))
            return "Unstable";
        if (color.startsWith("aborted"))
            return "Cancelled";
        return "Unknown";
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public JenkinsBuild getLastBuild() {
        return lastBuild;
    }

    public void setLastBuild(JenkinsBuild lastBuild) {
        this.lastBuild = lastBuild;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JenkinsBuild {
        private int number;
        private String url;
        private long timestamp;
        private String result;

        // Getters and Setters
        public int getNumber() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }
    }
}