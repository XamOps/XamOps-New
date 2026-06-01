package com.xammer.billops.dto;

import com.xammer.billops.domain.ClientIntakeSubmission;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Data
@AllArgsConstructor
public class ClientIntakeResponseDto {
    private Long id;
    private String name;
    private String companyName;
    private String email;
    private String phone;
    private String address;
    private String notes;
    private List<String> accountIds;
    private String submittedAt;
    private String status;

    public static ClientIntakeResponseDto from(ClientIntakeSubmission s) {
        List<String> ids = (s.getAccountIdsRaw() != null && !s.getAccountIdsRaw().isBlank())
                ? Arrays.asList(s.getAccountIdsRaw().split(","))
                : Collections.emptyList();
        return new ClientIntakeResponseDto(
                s.getId(),
                s.getName(),
                s.getCompanyName(),
                s.getEmail(),
                s.getPhone(),
                s.getAddress(),
                s.getNotes(),
                ids,
                s.getSubmittedAt().toString(),
                s.getStatus()
        );
    }
}
