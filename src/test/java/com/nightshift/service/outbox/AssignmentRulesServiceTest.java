package com.nightshift.service.outbox;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentRulesServiceTest {

    private AssignmentRulesService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentRulesService();
        service.loadRules();
    }

    @Test
    void matchesFarmerDomainByLoggerPrefix() {
        Incident incident = Incident.builder()
                .loggerName("com.example.farmer.FarmerSyncService")
                .serviceName("farmer-service")
                .severity(Severity.CRITICAL)
                .build();

        Assignee assignee = service.resolveAssignee(incident);
        assertThat(assignee.name()).isEqualTo("Dev A");
        assertThat(assignee.email()).isEqualTo("dev.a@example.com");
        assertThat(assignee.github()).isEqualTo("dev-a");
        assertThat(assignee.isDefault()).isFalse();
    }

    @Test
    void matchesPaymentServiceWithMajorSeverity() {
        Incident incident = Incident.builder()
                .serviceName("payment-service")
                .severity(Severity.MAJOR)
                .build();

        Assignee assignee = service.resolveAssignee(incident);
        assertThat(assignee.name()).isEqualTo("Dev B");
        assertThat(assignee.email()).isEqualTo("dev.b@example.com");
    }

    @Test
    void doesNotMatchPaymentServiceIfSeverityBelowMajor() {
        Incident incident = Incident.builder()
                .serviceName("payment-service")
                .severity(Severity.MINOR)
                .build();

        Assignee assignee = service.resolveAssignee(incident);
        assertThat(assignee.isDefault()).isTrue();
        assertThat(assignee.name()).isEqualTo("Platform on-call");
    }

    @Test
    void matchesBlockerEscalation() {
        Incident incident = Incident.builder()
                .serviceName("random-unknown-service")
                .severity(Severity.BLOCKER)
                .build();

        Assignee assignee = service.resolveAssignee(incident);
        assertThat(assignee.name()).isEqualTo("Platform on-call");
        assertThat(assignee.email()).isEqualTo("oncall@example.com");
    }

    @Test
    void fallsBackToDefaultAssigneeWhenNoMatch() {
        Incident incident = Incident.builder()
                .serviceName("random-unknown-service")
                .loggerName("org.apache.catalina")
                .severity(Severity.TRIVIAL)
                .build();

        Assignee assignee = service.resolveAssignee(incident);
        assertThat(assignee.isDefault()).isTrue();
        assertThat(assignee.email()).isEqualTo("oncall@example.com");
    }
}