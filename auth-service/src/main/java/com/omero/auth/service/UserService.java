package com.omero.auth.service;

import com.omero.auth.dto.UserDto;
import com.omero.auth.entity.User;
import com.omero.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Transactional
    public UserDto registerUser(UserDto userDto) {
        // Check if email already exists
        if (userRepository.findByEmail(userDto.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }

        // Create user entity
        User user = new User();
        user.setId(userDto.getId());
        user.setEmail(userDto.getEmail());
        user.setPassword(userDto.getPassword());
        user.setFirstName(userDto.getFirstName());
        user.setLastName(userDto.getLastName());
        user.setRole(userDto.getRole());
        user.setActive(userDto.isActive());
        user.setReseller(userDto.isReseller());
        user.setPlanName(userDto.getPlanName());
        user.setSubscriptionStartDate(userDto.getSubscriptionStartDate());
        user.setSubscriptionEndDate(userDto.getSubscriptionEndDate());
        user.setAuthorities(userDto.getAuthorities());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        // Save user
        User savedUser = userRepository.save(user);

        // Convert to DTO
        return UserDto.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .firstName(savedUser.getFirstName())
                .lastName(savedUser.getLastName())
                .role(savedUser.getRole())
                .isActive(savedUser.isActive())
                .isReseller(savedUser.isReseller())
                .planName(savedUser.getPlanName())
                .subscriptionStartDate(savedUser.getSubscriptionStartDate())
                .subscriptionEndDate(savedUser.getSubscriptionEndDate())
                .authorities(savedUser.getAuthorities())
                .build();
    }

    @Transactional
    public UserDto updateUser(UserDto userDto) {
        User user = userRepository.findById(userDto.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update user fields
        user.setFirstName(userDto.getFirstName());
        user.setLastName(userDto.getLastName());
        user.setPlanName(userDto.getPlanName());
        user.setSubscriptionStartDate(userDto.getSubscriptionStartDate());
        user.setSubscriptionEndDate(userDto.getSubscriptionEndDate());
        user.setAuthorities(userDto.getAuthorities());
        user.setUpdatedAt(LocalDateTime.now());

        // Save updated user
        User savedUser = userRepository.save(user);

        // Convert to DTO
        return UserDto.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .firstName(savedUser.getFirstName())
                .lastName(savedUser.getLastName())
                .role(savedUser.getRole())
                .isActive(savedUser.isActive())
                .isReseller(savedUser.isReseller())
                .planName(savedUser.getPlanName())
                .subscriptionStartDate(savedUser.getSubscriptionStartDate())
                .subscriptionEndDate(savedUser.getSubscriptionEndDate())
                .authorities(savedUser.getAuthorities())
                .build();
    }

    public UserDto getUserById(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole())
                .isActive(user.isActive())
                .isReseller(user.isReseller())
                .planName(user.getPlanName())
                .subscriptionStartDate(user.getSubscriptionStartDate())
                .subscriptionEndDate(user.getSubscriptionEndDate())
                .authorities(user.getAuthorities())
                .build();
    }

    public UserDto getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole())
                .isActive(user.isActive())
                .isReseller(user.isReseller())
                .planName(user.getPlanName())
                .subscriptionStartDate(user.getSubscriptionStartDate())
                .subscriptionEndDate(user.getSubscriptionEndDate())
                .authorities(user.getAuthorities())
                .build();
    }

    @Transactional
    public void deleteUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        userRepository.delete(user);
    }

    public boolean validateSubscription(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        LocalDateTime now = LocalDateTime.now();
        return user.isActive() &&
               user.getSubscriptionStartDate().isBefore(now) &&
               user.getSubscriptionEndDate().isAfter(now);
    }

    public boolean hasFeature(String userId, String feature) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return validateSubscription(userId) &&
               isFeatureEnabled(user.getPlanName(), feature);
    }

    private boolean isFeatureEnabled(String planName, String feature) {
        // Map of plan features
        switch (planName.toLowerCase()) {
            case "free":
                return feature.equals("chatbot.text") || feature.equals("chatbot.voice");
            case "bronze":
                return feature.equals("chatbot.text");
            case "silver":
                return feature.equals("chatbot.text") || feature.equals("chatbot.voice");
            case "gold":
                return feature.equals("chatbot.text") || 
                       feature.equals("chatbot.voice") || 
                       feature.equals("android_app");
            default:
                return false;
        }
    }
}
