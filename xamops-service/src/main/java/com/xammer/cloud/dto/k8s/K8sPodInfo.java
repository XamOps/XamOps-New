package com.xammer.cloud.dto.k8s;

public class K8sPodInfo {
    private String name;
    private String ready;
    private String status;
    private int restarts;
    private String age;
    private String nodeName;
    private String cpu;
    private String memory;

    public K8sPodInfo() {}

    public K8sPodInfo(String name, String ready, String status, int restarts, String age, String nodeName, String cpu, String memory) {
        this.name = name;
        this.ready = ready;
        this.status = status;
        this.restarts = restarts;
        this.age = age;
        this.nodeName = nodeName;
        this.cpu = cpu;
        this.memory = memory;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getReady() { return ready; }
    public void setReady(String ready) { this.ready = ready; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRestarts() { return restarts; }
    public void setRestarts(int restarts) { this.restarts = restarts; }

    public String getAge() { return age; }
    public void setAge(String age) { this.age = age; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }
}