package com.xammer.cloud.dto.k8s;

public class K8sNodeInfo {
    private String name;
    private String status;
    private String instanceType;
    private String availabilityZone;
    private String age;
    private String k8sVersion;
    private String cpuUsage;
    private String memUsage;

    public K8sNodeInfo() {}

    public K8sNodeInfo(String name, String status, String instanceType, String availabilityZone, String age, String k8sVersion, String cpuUsage, String memUsage) {
        this.name = name;
        this.status = status;
        this.instanceType = instanceType;
        this.availabilityZone = availabilityZone;
        this.age = age;
        this.k8sVersion = k8sVersion;
        this.cpuUsage = cpuUsage;
        this.memUsage = memUsage;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getAge() { return age; }
    public void setAge(String age) { this.age = age; }

    public String getK8sVersion() { return k8sVersion; }
    public void setK8sVersion(String k8sVersion) { this.k8sVersion = k8sVersion; }

    public String getCpuUsage() { return cpuUsage; }
    public void setCpuUsage(String cpuUsage) { this.cpuUsage = cpuUsage; }

    public String getMemUsage() { return memUsage; }
    public void setMemUsage(String memUsage) { this.memUsage = memUsage; }
}