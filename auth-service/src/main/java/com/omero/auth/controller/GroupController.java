package com.omero.auth.controller;

import com.omero.auth.entity.Group;
import com.omero.auth.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Set;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    @Autowired
    private GroupService groupService;

    @PostMapping
    public ResponseEntity<Group> createGroup(
            @RequestParam String name,
            @RequestParam String type,
            @RequestParam String planName,
            @RequestParam LocalDateTime subscriptionStartDate,
            @RequestParam LocalDateTime subscriptionEndDate) {
        Group group = groupService.createGroup(
                name,
                type,
                planName,
                subscriptionStartDate,
                subscriptionEndDate
        );
        return ResponseEntity.ok(group);
    }

    @PostMapping("/{groupId}/users/{userId}")
    public ResponseEntity<Group> addUserToGroup(
            @PathVariable String groupId,
            @PathVariable String userId) {
        Group group = groupService.addUserToGroup(groupId, userId);
        return ResponseEntity.ok(group);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<Set<Group>> getGroupsByUser(@PathVariable String userId) {
        Set<Group> groups = groupService.getGroupsByUser(userId);
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<Group> getGroup(@PathVariable String groupId) {
        return groupService.getGroupById(groupId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
