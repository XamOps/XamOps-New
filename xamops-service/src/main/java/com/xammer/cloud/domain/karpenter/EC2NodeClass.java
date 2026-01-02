package com.xammer.cloud.domain.karpenter;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Group("karpenter.k8s.aws")
@Version("v1beta1")
@Kind("EC2NodeClass")
public class EC2NodeClass extends CustomResource<EC2NodeClassSpec, Void> implements Namespaced {
    // No explicit body needed; Lombok @Data handles getters/setters for 'spec'
    // inherited from CustomResource
}