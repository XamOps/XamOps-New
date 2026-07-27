package com.xammer.cloud.controller.compai;

import com.xammer.cloud.dto.compai.CreateRiskRequestDto;
import com.xammer.cloud.dto.compai.CreateTaskRequestDto;
import com.xammer.cloud.dto.compai.CreateVendorRequestDto;
import com.xammer.cloud.dto.compai.InviteMemberRequestDto;
import com.xammer.cloud.dto.compai.UpdatePersonRequestDto;
import com.xammer.cloud.dto.compai.UpdateRiskRequestDto;
import com.xammer.cloud.dto.compai.UpdateTaskRequestDto;
import com.xammer.cloud.dto.compai.UpdateVendorRequestDto;
import com.xammer.cloud.service.compai.CompAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SOC2 Program endpoints. Backed by a self-hosted comp.ai instance via
 * {@link CompAiService}; the frontend "SOC2 Program" page consumes these.
 *
 * <p><b>SCOPE — single-org demo, NOT multi-tenant.</b> These endpoints serve one
 * shared comp.ai organization (fictional demo data) to every authenticated XamOps
 * user, gated only by {@code .anyRequest().authenticated()} — the same model as
 * ComplianceOps / XamSecure. The publish/update/create endpoints below MUTATE
 * that shared demo org for every authenticated user (no per-user ownership
 * check) — acceptable for the single fictional-org demo, not for production.
 * Before this is used with real comp.ai data or across tenants, the following
 * are REQUIRED (see the automated security review + the self-hosting runbook):
 * <ul>
 *   <li>Resolve the comp.ai API key / base URL <i>per tenant</i> (from the
 *       authenticated principal / CloudAccount) instead of the JVM-wide
 *       {@code ${compai.api-key}}, and tenant-scope access.</li>
 *   <li>Namespace the Redis cache keys by tenant (e.g. {@code compai:controls:<tenantId>})
 *       in {@link CompAiService} so one tenant's data can't be served to another.</li>
 *   <li>Front comp.ai with TLS and use {@code https://} — the key is currently sent
 *       in cleartext over HTTP (acceptable only for the throwaway demo instance);
 *       rotate the demo key afterward.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/soc2")
@RequiredArgsConstructor
public class CompAiController {

    private final CompAiService compAiService;

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        return ResponseEntity.ok(compAiService.getOverview());
    }

    @GetMapping("/frameworks")
    public ResponseEntity<?> frameworks() {
        return ResponseEntity.ok(compAiService.getFrameworks());
    }

    @GetMapping("/controls")
    public ResponseEntity<?> controls() {
        return ResponseEntity.ok(compAiService.getControls());
    }

    @GetMapping("/policies")
    public ResponseEntity<?> policies() {
        return ResponseEntity.ok(compAiService.getPolicies());
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> tasks() {
        return ResponseEntity.ok(compAiService.getTasks());
    }

    @GetMapping("/personnel")
    public ResponseEntity<?> personnel() {
        return ResponseEntity.ok(compAiService.getPersonnel());
    }

    @GetMapping("/vendors")
    public ResponseEntity<?> vendors() {
        return ResponseEntity.ok(compAiService.getVendors());
    }

    @GetMapping("/risks")
    public ResponseEntity<?> risks() {
        return ResponseEntity.ok(compAiService.getRisks());
    }

    @GetMapping("/cloud-tests")
    public ResponseEntity<?> cloudTests() {
        return ResponseEntity.ok(compAiService.getCloudTests());
    }

    @GetMapping("/documents")
    public ResponseEntity<?> documents() {
        return ResponseEntity.ok(compAiService.getDocuments());
    }

    @GetMapping("/integrations")
    public ResponseEntity<?> integrations() {
        return ResponseEntity.ok(compAiService.getIntegrations());
    }

    @PostMapping("/policies/{id}/publish")
    public ResponseEntity<?> publishPolicy(@PathVariable String id) {
        return ResponseEntity.ok(compAiService.publishPolicy(id));
    }

    @PatchMapping("/tasks/{id}")
    public ResponseEntity<?> updateTask(@PathVariable String id, @RequestBody UpdateTaskRequestDto request) {
        return ResponseEntity.ok(compAiService.updateTask(id, request));
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(@RequestBody CreateTaskRequestDto request) {
        return ResponseEntity.ok(compAiService.createTask(request));
    }

    @PostMapping("/vendors")
    public ResponseEntity<?> createVendor(@RequestBody CreateVendorRequestDto request) {
        return ResponseEntity.ok(compAiService.createVendor(request));
    }

    @PatchMapping("/vendors/{id}")
    public ResponseEntity<?> updateVendor(@PathVariable String id, @RequestBody UpdateVendorRequestDto request) {
        return ResponseEntity.ok(compAiService.updateVendor(id, request));
    }

    @DeleteMapping("/vendors/{id}")
    public ResponseEntity<?> deleteVendor(@PathVariable String id) {
        compAiService.deleteVendor(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/risks")
    public ResponseEntity<?> createRisk(@RequestBody CreateRiskRequestDto request) {
        return ResponseEntity.ok(compAiService.createRisk(request));
    }

    @PatchMapping("/risks/{id}")
    public ResponseEntity<?> updateRisk(@PathVariable String id, @RequestBody UpdateRiskRequestDto request) {
        return ResponseEntity.ok(compAiService.updateRisk(id, request));
    }

    @DeleteMapping("/risks/{id}")
    public ResponseEntity<?> deleteRisk(@PathVariable String id) {
        compAiService.deleteRisk(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/controls/{id}")
    public ResponseEntity<?> deleteControl(@PathVariable String id) {
        compAiService.deleteControl(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/policies/{id}/archive")
    public ResponseEntity<?> setPolicyArchived(@PathVariable String id, @RequestBody java.util.Map<String, Boolean> body) {
        return ResponseEntity.ok(compAiService.setPolicyArchived(id, Boolean.TRUE.equals(body.get("archived"))));
    }

    @PostMapping("/personnel/invite")
    public ResponseEntity<?> inviteMember(@RequestBody InviteMemberRequestDto request) {
        compAiService.inviteMember(request);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/personnel/{id}")
    public ResponseEntity<?> updatePerson(@PathVariable String id, @RequestBody UpdatePersonRequestDto request) {
        return ResponseEntity.ok(compAiService.updatePerson(id, request));
    }

    @DeleteMapping("/personnel/{id}")
    public ResponseEntity<?> deletePerson(@PathVariable String id) {
        compAiService.deletePerson(id);
        return ResponseEntity.noContent().build();
    }
}
