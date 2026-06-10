package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ScriptParameter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScriptParameterRepository extends JpaRepository<ScriptParameter, Long> {

    /**
     * Fetch all parameters for a script ordered by their display order so the
     * generated customization form renders fields in the intended sequence.
     */
    List<ScriptParameter> findByScriptIdOrderByDisplayOrderAsc(Long scriptId);
}
