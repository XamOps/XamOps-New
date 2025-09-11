package com.xammer.billops.controller;

import com.xammer.billops.dto.UserProfileDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/api/user")
public class UserProfileController {

    @GetMapping("/profile")
    public ResponseEntity<UserProfileDto> getUserProfile() {
        // This is a mock response. In a real application, you would get the
        // authenticated user from the SecurityContextHolder.
        UserProfileDto userProfile = new UserProfileDto("billops-user", Arrays.asList("ROLE_BILLOPS"));
        return ResponseEntity.ok(userProfile);
    }
}