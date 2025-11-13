package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
// --- DTO IMPORTS ---
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
// --- FABRIC8 IMPORTS ---
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
// --- OTHER IMPORTS ---
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy; 
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function; 
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Base64;

@Service
public class EksAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(EksAutomationService.class);

    private final AwsClientProvider awsClientProvider;
    private final KubernetesManifestService manifestService;
    private final EksService eksService; 

    @Autowired
    public EksAutomationService(AwsClientProvider awsClientProvider, 
                                KubernetesManifestService manifestService,
                                @Lazy EksService eksService) { 
        this.awsClientProvider = awsClientProvider;
        this.manifestService = manifestService;
        this.eksService = eksService; 
    }

    // ... (installOpenCost and enableContainerInsights methods are unchanged) ...
    public boolean installOpenCost(CloudAccount account, String clusterName, String region) {
        logger.info("Starting OpenCost installation via manifests for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            if (client.apps().deployments().inNamespace("opencost").withName("opencost").get() != null) {
                logger.warn("OpenCost deployment already exists in namespace 'opencost'. Skipping installation.");
                return true;
            }
            manifestService.applyManifests(client, "classpath:kubernetes/opencost/*.yaml");
            logger.info("Successfully applied all OpenCost manifests to cluster {}.", clusterName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to install OpenCost on cluster {}", clusterName, e);
            return false;
        }
    }

    public boolean enableContainerInsights(CloudAccount account, String clusterName, String region) {
        logger.info("Starting automated Container Insights installation for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            ClassPathResource resource = new ClassPathResource("kubernetes/container-insights-template.yaml");
            String manifestTemplate;
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                manifestTemplate = FileCopyUtils.copyToString(reader);
            }
            String finalManifest = manifestTemplate
                .replace("{{cluster_name}}", clusterName)
                .replace("{{region_name}}", region)
                .replace("{{http_server_toggle}}", "\"On\"")
                .replace("{{http_server_port}}", "\"2020\"")
                .replace("{{read_from_head}}", "\"Off\"")
                .replace("{{read_from_tail}}", "\"On\"");
            client.load(new java.io.ByteArrayInputStream(finalManifest.getBytes())).createOrReplace();
            logger.info("Successfully applied Container Insights manifests to cluster {}.", clusterName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to enable Container Insights on cluster {}", clusterName, e);
            return false;
        }
    }


    // --- MODIFIED METHOD FOR GETTING NODES (FIXED) ---
    public List<K8sNodeInfo> getClusterNodes(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching nodes from Kubernetes API for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            
            // 1. Get node list from K8s API
            List<K8sNodeInfo> apiNodes = client.nodes().list().getItems().stream()
                .map(this::mapNodeToDto)
                .collect(Collectors.toList());

            // 2. Get metrics from EksService (CloudWatch)
            Map<String, K8sNodeInfo> metricsMap = eksService.getK8sNodes(account.getAwsAccountId(), clusterName, false)
                .join() // Wait for the CompletableFuture
                .stream()
                .collect(Collectors.toMap(K8sNodeInfo::getName, Function.identity()));

            // 3. Merge metrics into the API node list (USING CORRECTED GETTERS)
            for (K8sNodeInfo node : apiNodes) {
                K8sNodeInfo metrics = metricsMap.get(node.getName());
                if (metrics != null) {
                    node.setCpuUsage(metrics.getCpuUsage()); // <-- FIX
                    node.setMemUsage(metrics.getMemUsage()); // <-- FIX
                }
            }
            
            return apiNodes;

        } catch (Exception e) {
            logger.error("Failed to get nodes from K8s API for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // --- (getClusterPods and getClusterDeployments are unchanged) ---
    public List<K8sPodInfo> getClusterPods(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching pods from Kubernetes API for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            return client.pods().inAnyNamespace().list().getItems().stream()
                .map(this::mapPodToDto)
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to get pods from K8s API for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    public List<K8sDeploymentInfo> getClusterDeployments(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching deployments from Kubernetes API for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            return client.apps().deployments().inAnyNamespace().list().getItems().stream()
                .map(this::mapDeploymentToDto)
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to get deployments from K8s API for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }
    
    // ... (getKubernetesClientForEks and generateEksToken are unchanged) ...
    private KubernetesClient getKubernetesClientForEks(CloudAccount account, String clusterName, String region) {
        EksClient eksClient = awsClientProvider.getEksClient(account, region);
        Cluster cluster = eksClient.describeCluster(req -> req.name(clusterName)).cluster();
        String endpoint = cluster.endpoint();
        String caData = cluster.certificateAuthority().data();
        String token = generateEksToken(account, region);

        Config config = new Config();
        config.setMasterUrl(endpoint);
        config.setCaCertData(caData);
        config.setTrustCerts(true); 
        config.setOauthToken(token);
        config.setRequestTimeout(10000); 
        config.setConnectionTimeout(10000); 

        return new DefaultKubernetesClient(config);
    }

    
    private String generateEksToken(CloudAccount account, String region) {
        try {
            AwsCredentials credentials = awsClientProvider.getCredentialsProvider(account).resolveCredentials();
            logger.info("Java app using access key: {}", credentials.accessKeyId());

            try {
                StsClient stsClient = StsClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(region))
                    .credentialsProvider(awsClientProvider.getCredentialsProvider(account))
                    .build();
                
                GetCallerIdentityResponse identity = stsClient.getCallerIdentity();
                logger.info("Java app caller identity ARN: {}", identity.arn());
            } catch (Exception stsException) {
                logger.error("Failed to get caller identity via STS", stsException);
            }

            String host = "sts." + region + ".amazonaws.com";
            String endpoint = "https://" + host + "/";
            Instant now = Instant.now();
            
            String timestamp = now.atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            
            String dateStamp = now.atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            
            String credentialScope = dateStamp + "/" + region + "/sts/aws4_request";
            
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Action", "GetCallerIdentity");
            params.put("Version", "2011-06-15");
            params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
            params.put("X-Amz-Credential", credentials.accessKeyId() + "/" + credentialScope);
            params.put("X-Amz-Date", timestamp);
            params.put("X-Amz-Expires", "60");
            params.put("X-Amz-SignedHeaders", "host;x-k8s-aws-id");
            
            if (credentials instanceof AwsSessionCredentials) {
                AwsSessionCredentials sessionCreds = (AwsSessionCredentials) credentials;
                params.put("X-Amz-Security-Token", sessionCreds.sessionToken());
            }
            
            String queryString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
            
            String canonicalHeaders = "host:" + host + "\n" + "x-k8s-aws-id:\n";
            String canonicalRequest = "GET\n" +
                "/\n" +
                queryString + "\n" +
                canonicalHeaders + "\n" +
                "host;x-k8s-aws-id\n" +
                "UNSIGNED-PAYLOAD";
            
            String stringToSign = "AWS4-HMAC-SHA256\n" +
                timestamp + "\n" +
                credentialScope + "\n" +
                sha256Hex(canonicalRequest);
            
            String signature = calculateSignature(credentials.secretAccessKey(), 
                stringToSign, dateStamp, region, "sts");
            
            String finalQueryString = queryString + "&X-Amz-Signature=" + signature;
            String presignedUrl = endpoint + "?" + finalQueryString;
            
            String token = "k8s-aws-v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(presignedUrl.getBytes(StandardCharsets.UTF_8));
            
            logger.info("Successfully generated EKS authentication token");
            return token;
            
        } catch (Exception e) {
            logger.error("Failed to generate EKS token", e);
            throw new RuntimeException("Failed to generate EKS token", e);
        }
    }

    // --- HELPER METHODS FOR DTO MAPPING (FIXED) ---

    private K8sNodeInfo mapNodeToDto(Node node) {
        String name = node.getMetadata().getName();
        String status = node.getStatus().getConditions().stream()
            .filter(c -> "Ready".equals(c.getType()))
            .findFirst()
            .map(c -> "True".equalsIgnoreCase(c.getStatus()) ? "Ready" : "NotReady")
            .orElse("Unknown");
        String instanceType = node.getMetadata().getLabels().get("node.kubernetes.io/instance-type");
        String k8sVersion = node.getStatus().getNodeInfo().getKubeletVersion(); // DTO field is k8sVersion
        String availabilityZone = node.getMetadata().getLabels().get("topology.kubernetes.io/zone"); // DTO field is availabilityZone
        String age = formatAge(node.getMetadata().getCreationTimestamp()); // DTO field is age
        
        // Match K8sNodeInfo DTO:
        // (name, status, instanceType, availabilityZone, age, k8sVersion, cpuUsage, memUsage)
        return new K8sNodeInfo(name, status, instanceType, availabilityZone, age, k8sVersion, null, null);
    }

    private K8sPodInfo mapPodToDto(Pod pod) {
        String name = pod.getMetadata().getName();
        String status = pod.getStatus().getPhase();
        String age = formatAge(pod.getMetadata().getCreationTimestamp());
        String nodeName = pod.getSpec().getNodeName();
        
        int restarts = 0;
        long readyContainers = 0;
        long totalContainers = 0;
        
        if (pod.getStatus().getContainerStatuses() != null) {
             restarts = pod.getStatus().getContainerStatuses().stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
            
            readyContainers = pod.getStatus().getContainerStatuses().stream()
                .filter(ContainerStatus::getReady)
                .count();

            totalContainers = pod.getStatus().getContainerStatuses().size();
        }

        String ready = String.format("%d/%d", readyContainers, totalContainers);
        
        // Match K8sPodInfo DTO:
        // (String name, String ready, String status, int restarts, String age, String nodeName, String cpu, String memory)
        return new K8sPodInfo(name, ready, status, restarts, age, nodeName, null, null); 
    }

    private K8sDeploymentInfo mapDeploymentToDto(Deployment dep) {
        String name = dep.getMetadata().getName();
        String age = formatAge(dep.getMetadata().getCreationTimestamp());

        int replicas = dep.getSpec().getReplicas() != null ? dep.getSpec().getReplicas() : 0;
        int readyReplicas = dep.getStatus().getReadyReplicas() != null ? dep.getStatus().getReadyReplicas() : 0;
        int upToDate = dep.getStatus().getUpdatedReplicas() != null ? dep.getStatus().getUpdatedReplicas() : 0;
        int available = dep.getStatus().getAvailableReplicas() != null ? dep.getStatus().getAvailableReplicas() : 0;

        String ready = String.format("%d/%d", readyReplicas, replicas);

        // Match K8sDeploymentInfo DTO:
        // (String name, String ready, int upToDate, int available, String age)
        return new K8sDeploymentInfo(name, ready, upToDate, available, age);
    }
    
    private String formatAge(String creationTimestamp) {
        if (creationTimestamp == null) {
            return "Unknown";
        }
        try {
            ZonedDateTime creationTime = ZonedDateTime.parse(creationTimestamp);
            Duration age = Duration.between(creationTime, ZonedDateTime.now());
            
            if (age.toDays() > 0) {
                return age.toDays() + "d";
            } else if (age.toHours() > 0) {
                return age.toHours() + "h";
            } else if (age.toMinutes() > 0) {
                return age.toMinutes() + "m";
            } else {
                return age.getSeconds() + "s";
            }
        } catch (Exception e) {
            logger.warn("Failed to parse creation timestamp: {}", creationTimestamp, e);
            return "Unknown";
        }
    }

    // --- Utility methods (no changes) ---

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
        } catch (Exception e) {
            logger.error("URL encoding failed for value: {}", value, e);
            throw new RuntimeException(e);
        }
    }

    private String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            logger.error("SHA256 hashing failed", e);
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private String calculateSignature(String secretKey, String stringToSign, 
                                    String dateStamp, String region, String service) {
        try {
            byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
            byte[] kDate = hmacSha256(kSecret, dateStamp);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, service);
            byte[] kSigning = hmacSha256(kService, "aws4_request");
            
            byte[] signature = hmacSha256(kSigning, stringToSign);
            return bytesToHex(signature);
        } catch (Exception e) {
            logger.error("Failed to calculate AWS signature", e);
            throw new RuntimeException("Failed to calculate signature", e);
        }
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}