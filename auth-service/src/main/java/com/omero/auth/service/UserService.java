package com.omero.auth.service;

import com.omero.auth.entity.User;
import com.omero.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PlanAccessService planAccessService;

    public UserService(UserRepository userRepository, PlanAccessService planAccessService) {
        this.userRepository = userRepository;
        this.planAccessService = planAccessService;
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public User getUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public boolean hasFeature(UUID userId, String feature) {
        return planAccessService.hasFeature(userId, feature);
    }
}
