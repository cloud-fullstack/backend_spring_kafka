package com.omero.auth.service;

import com.omero.auth.entity.Group;
import com.omero.auth.entity.User;
import com.omero.auth.repository.GroupRepository;
import com.omero.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class GroupService {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Transactional
    public Group createGroup(String name, String type, String planName, LocalDateTime subscriptionStartDate, LocalDateTime subscriptionEndDate) {
        Group group = new Group();
        group.setId(UUID.randomUUID().toString());
        group.setName(name);
        group.setType(type);
        group.setPlanName(planName);
        group.setSubscriptionStartDate(subscriptionStartDate);
        group.setSubscriptionEndDate(subscriptionEndDate);
        group.setIsActive(true);
        group.setCreatedAt(LocalDateTime.now());
        
        return groupRepository.save(group);
    }

    @Transactional
    public Group addUserToGroup(String groupId, String userId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        group.getUsers().add(user);
        user.getGroups().add(group);

        return groupRepository.save(group);
    }

    public Set<Group> getGroupsByUser(String userId) {
        return userRepository.findById(userId)
                .map(User::getGroups)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public Optional<Group> getGroupById(String groupId) {
        return groupRepository.findById(groupId);
    }
}
