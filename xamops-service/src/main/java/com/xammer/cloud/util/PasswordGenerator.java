package com.xammer.cloud.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordGenerator {

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String plainPassword = "t2D2h9TvdUW8WDlV"; // Change this to your desired password
        String hashedPassword = encoder.encode(plainPassword);

        System.out.println("Your Bcrypt hash is:");
        System.out.println(hashedPassword);
    }
}

