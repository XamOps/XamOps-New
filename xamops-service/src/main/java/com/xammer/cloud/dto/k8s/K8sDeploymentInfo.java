package com.xammer.cloud.dto.k8s;

public class K8sDeploymentInfo {
    private String name;
    private String ready;
    private int upToDate;
    private int available;
    private String age;

    public K8sDeploymentInfo() {}

    public K8sDeploymentInfo(String name, String ready, int upToDate, int available, String age) {
        this.name = name;
        this.ready = ready;
        this.upToDate = upToDate;
        this.available = available;
        this.age = age;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getReady() { return ready; }
    public void setReady(String ready) { this.ready = ready; }

    public int getUpToDate() { return upToDate; }
    public void setUpToDate(int upToDate) { this.upToDate = upToDate; }

    public int getAvailable() { return available; }
    public void setAvailable(int available) { this.available = available; }

    public String getAge() { return age; }
    public void setAge(String age) { this.age = age; }
}