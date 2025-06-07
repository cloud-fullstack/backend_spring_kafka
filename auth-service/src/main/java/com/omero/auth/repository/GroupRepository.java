package com.omero.auth.repository;

import com.omero.auth.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface GroupRepository extends JpaRepository<Group, String> {
    Optional<Group> findByName(String name);
    boolean existsByName(String name);
    Set<Group> findByPlanName(String planName);
}
