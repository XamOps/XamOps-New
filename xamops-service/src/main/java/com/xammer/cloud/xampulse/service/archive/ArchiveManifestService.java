package com.xammer.cloud.xampulse.service.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Reads and updates the per-day manifest objects. An entry for an existing key is replaced, so retries are idempotent. */
@Service("xpArchiveManifestService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ArchiveManifestService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ArchiveStore store;

    public ArchiveManifestService(ArchiveStore store) {
        this.store = store;
    }

    public Optional<ArchiveManifest> read(XpAccountConfig cfg, String awsAccountId, LocalDate day) {
        return store.getText(cfg, ArchiveKeyLayout.manifestKey(awsAccountId, day)).map(ArchiveManifestService::parse);
    }

    public void addEntry(XpAccountConfig cfg, String awsAccountId, LocalDate day, ArchiveManifest.Entry entry) {
        ArchiveManifest current = read(cfg, awsAccountId, day).orElse(null);
        store.putText(cfg, ArchiveKeyLayout.manifestKey(awsAccountId, day), write(merge(current, awsAccountId, day, entry)));
    }

    static ArchiveManifest merge(ArchiveManifest current, String awsAccountId, LocalDate day, ArchiveManifest.Entry entry) {
        List<ArchiveManifest.Entry> files = new ArrayList<>();
        if (current != null && current.files() != null) {
            current.files().stream().filter(e -> !e.key().equals(entry.key())).forEach(files::add);
        }
        files.add(entry);
        files.sort(Comparator.comparing(ArchiveManifest.Entry::key));
        return new ArchiveManifest(ArchiveManifest.SCHEMA_VERSION, awsAccountId, day.toString(), List.copyOf(files));
    }

    static String write(ArchiveManifest m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest could not be serialised", e);
        }
    }

    static ArchiveManifest parse(String json) {
        try {
            return JSON.readValue(json, ArchiveManifest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest is not valid JSON", e);
        }
    }
}
