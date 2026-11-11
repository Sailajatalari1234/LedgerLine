package com.ledgerline.audit;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only. SecurityConfig limits /api/audit/** to ADMIN and AUDITOR. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/logs")
    public List<AuditService.AuditEntry> logs(@RequestParam(defaultValue = "100") int limit) {
        return auditService.latest(limit);
    }
}