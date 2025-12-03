package com.xammer.cloud.controller;

import com.xammer.cloud.domain.ArchitectureBlueprint;
import com.xammer.cloud.repository.ArchitectureBlueprintRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/xamops/design")
public class ArchitectureDesignController {

    @Autowired
    private ArchitectureBlueprintRepository repository;

    @GetMapping("/list")
    public List<ArchitectureBlueprint> getAllBlueprints() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ArchitectureBlueprint> getBlueprint(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/save")
    public ArchitectureBlueprint saveBlueprint(@RequestBody ArchitectureBlueprint blueprint) {
        return repository.save(blueprint);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBlueprint(@PathVariable Long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}