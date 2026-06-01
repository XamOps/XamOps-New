package com.xammer.billops.dto;

import lombok.Data;
import java.util.List;

@Data
public class ClientIntakeRequestDto {
    private String name;
    private String companyName;
    private String email;
    private String phone;
    private String address;
    private String notes;
    private List<String> accountIds;
}
