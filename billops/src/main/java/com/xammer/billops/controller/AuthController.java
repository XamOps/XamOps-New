package com.xammer.billops.controller;

import com.xammer.billops.domain.User;
import com.xammer.billops.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {

    @Autowired
    private UserService userService;

    // ... (login and showRegistrationForm methods are unchanged) ...
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String showRegistrationForm(Model model) {
        model.addAttribute("user", new User());
        return "register";
    }


    // --- UPDATED REGISTRATION PROCESSING METHOD ---
    @PostMapping("/register")
    public String processRegistration(@ModelAttribute User user) {
        // Check if the username is already taken
        if (userService.findByUsername(user.getUsername()).isPresent()) {
            // Redirect back to the registration form with an error
            return "redirect:/register?error";
        }

        userService.saveUser(user);
        return "redirect:/login?success";
    }
}