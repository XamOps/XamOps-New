package com.xammer.cloud.dto.dbops;

public class ExplainResultDto {
    private String requestId;
    private String status;
    private String planJson;
    private String error;

    public ExplainResultDto(String requestId, String status, String planJson, String error) {
        this.requestId = requestId;
        this.status    = status;
        this.planJson  = planJson;
        this.error     = error;
    }

    public String getRequestId() { return requestId; }
    public String getStatus()    { return status; }
    public String getPlanJson()  { return planJson; }
    public String getError()     { return error; }
}
