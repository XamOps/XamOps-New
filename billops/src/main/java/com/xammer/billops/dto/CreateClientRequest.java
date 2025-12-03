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
}