package com.xammer.cloud.permissions;

/**
 * The user-facing areas of XamOps / BillOps / MediaOps that a missing cloud
 * permission can blank out.
 *
 * A denial is recorded against the IAM action that failed; this enum is how we
 * translate that back into language a customer understands ("Rightsizing is
 * empty") instead of an SDK operation name they have never seen.
 *
 * {@code route} is the SPA path the frontend uses to decide whether a gap is
 * relevant to the page currently on screen.
 */
public enum XamOpsFeature {

    DASHBOARD("Dashboard", "/dashboard"),
    CLOUD_LIST("Cloud List", "/cloudlist"),
    FINOPS("FinOps", "/finops"),
    COST("Cost Explorer", "/cost"),
    WASTE("Waste Management", "/waste"),
    RIGHTSIZING("Rightsizing", "/rightsizing"),
    EBS_RIGHTSIZING("EBS Rightsizing", "/ebs-rightsizing"),
    RESERVATIONS("Reservations & Savings Plans", "/reservation"),
    SECURITY("XamSecure", "/xamsecure"),
    KUBERNETES("Cloud K8s", "/cloudk8s"),
    PERFORMANCE("Performance", "/performance"),
    CLOUD_MAP("Cloud Map", "/cloudmap"),
    ALERTS("Alerts", "/alerts"),
    CLOUDSITTER("CloudSitter", "/cloudsitter"),
    SPOT_AUTOMATION("Spot Automation", "/spot-automation"),
    DBOPS("DbOps", "/dbops"),
    DATAOPS("DataOps", "/dataops"),
    OBSERVABILITY("Observability", "/observability"),
    CICD("CI/CD Pipelines", "/cicd-pipelines"),
    AIOPS("AIOps", "/aiops"),
    SRE("SRE", "/sre"),
    COMPLIANCE("ComplianceOps", "/complianceops"),
    BILLOPS("BillOps Billing", "/billops-dashboard"),
    MEDIAOPS("MediaOps", "/media-ops");

    private final String label;
    private final String route;

    XamOpsFeature(String label, String route) {
        this.label = label;
        this.route = route;
    }

    public String getLabel() {
        return label;
    }

    public String getRoute() {
        return route;
    }
}
