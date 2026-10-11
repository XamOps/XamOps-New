package com.xammer.cloud.dto.k8s;

import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.exception.NoUserPermissionDetail;

/**
 * The one refusal body for {@code /api/eks/autoscaling/**} and the capabilities endpoint
 * (design §6.6): {@code {"success": false, "code": "K8S_…", "message": "…", "field": null, "detail": null}}.
 *
 * <p>
 * {@code detail} is null unless the code is {@code K8S_NO_USER_PERMISSION} (design-review
 * NIT 6). {@code message} is always fixed copy, never exception text.
 */
public record K8sRefusalBody(boolean success, String code, String message, String field, String detail) {

    public static K8sRefusalBody of(K8sAutomationReason reason, String message, String field,
                                    NoUserPermissionDetail detail) {
        String detailName = reason == K8sAutomationReason.NO_USER_PERMISSION && detail != null ? detail.name() : null;
        return new K8sRefusalBody(false, reason.code(), message != null ? message : reason.defaultMessage(),
                field, detailName);
    }

    public static K8sRefusalBody of(K8sAutomationRefusedException e) {
        return of(e.getReason(), e.getMessage(), e.getField(), e.getDetail());
    }
}
