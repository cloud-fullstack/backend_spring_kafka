package com.omero.auth.repository;

import com.omero.auth.entity.BusinessVertical;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessVerticalRepository extends JpaRepository<BusinessVertical, String> {
    boolean existsByCodeAndActiveTrue(String code);
}
