package com.xammer.billops.dto;

import lombok.Data;

@Data
public class CreateClientRequest {
    private String name;
    private String address;
    private String gstin;
    private String stateName;
    private String stateCode;
    private String pan;
    private String cin;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }
    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
    public String getCin() { return cin; }
    public void setCin(String cin) { this.cin = cin; }
}