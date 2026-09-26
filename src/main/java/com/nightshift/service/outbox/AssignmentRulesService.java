package com.nightshift.service.outbox;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.enums.Severity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates assignment rules loaded from {@code config/assignment-rules.yml}
 * to assign an incident / PR to the responsible engineer or team.
 */
@Slf4j
@Service
public class AssignmentRulesService {

    private final List<RuleDefinition> rules = new ArrayList<>();
    private Assignee defaultAssignee = new Assignee("Platform on-call", "oncall@example.com", "oncall", true);

    @PostConstruct
    public void init() {
        loadRules();
    }

    public void loadRules() {
        rules.clear();
        try {
            Resource fsResource = new FileSystemResource("config/assignment-rules.yml");
            Resource resource = fsResource.exists() ? fsResource : new ClassPathResource("assignment-rules.yml");

            if (!resource.exists()) {
                log.warn("AssignmentRulesService: no assignment-rules.yml found; using default assignee");
                return;
            }

            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> yaml = new Yaml().load(in);
                if (yaml == null) return;

                // Load default assignee
                Map<String, Object> defMap = (Map<String, Object>) yaml.get("default_assignee");
                if (defMap != null) {
                    defaultAssignee = new Assignee(
                            (String) defMap.getOrDefault("name", "Platform on-call"),
                            (String) defMap.getOrDefault("email", "oncall@example.com"),
                            (String) defMap.getOrDefault("github", "oncall"),
                            true
                    );
                }

                // Load rules
                List<Map<String, Object>> ruleList = (List<Map<String, Object>>) yaml.get("rules");
                if (ruleList != null) {
                    for (Map<String, Object> ruleMap : ruleList) {
                        String name = (String) ruleMap.get("name");
                        Map<String, Object> match = (Map<String, Object>) ruleMap.get("match");
                        Map<String, Object> assigneeMap = (Map<String, Object>) ruleMap.get("assignee");

                        String loggerPrefix = match != null ? (String) match.get("logger_prefix") : null;
                        String service = match != null ? (String) match.get("service") : null;
                        String severityAtLeast = match != null ? (String) match.get("severity_at_least") : null;

                        Assignee assignee = null;
                        if (assigneeMap != null) {
                            assignee = new Assignee(
                                    (String) assigneeMap.get("name"),
                                    (String) assigneeMap.get("email"),
                                    (String) assigneeMap.get("github"),
                                    false
                            );
                        }

                        if (assignee != null) {
                            rules.add(new RuleDefinition(name, loggerPrefix, service, severityAtLeast, assignee));
                        }
                    }
                }
                log.info("AssignmentRulesService: loaded {} assignment rules", rules.size());
            }
        } catch (Exception e) {
            log.error("AssignmentRulesService: failed to load assignment-rules.yml: {}", e.getMessage(), e);
        }
    }

    /**
     * Resolves the assignee for the given incident. First matching rule wins;
     * if no rule matches, returns the default assignee.
     */
    public Assignee resolveAssignee(Incident incident) {
        if (incident == null) {
            return defaultAssignee;
        }

        for (RuleDefinition rule : rules) {
            if (rule.matches(incident)) {
                log.debug("Rule '{}' matched incident {}", rule.name(), incident.getFingerprint());
                return rule.assignee();
            }
        }

        log.debug("No rule matched incident {}; falling back to default assignee", incident.getFingerprint());
        return defaultAssignee;
    }

    public Assignee getDefaultAssignee() {
        return defaultAssignee;
    }

    public List<RuleDefinition> getRules() {
        return List.copyOf(rules);
    }

    public record RuleDefinition(
            String name,
            String loggerPrefix,
            String service,
            String severityAtLeast,
            Assignee assignee
    ) {
        public boolean matches(Incident incident) {
            if (loggerPrefix != null && !loggerPrefix.isBlank()) {
                if (incident.getLoggerName() == null || !incident.getLoggerName().startsWith(loggerPrefix)) {
                    return false;
                }
            }

            if (service != null && !service.isBlank()) {
                if (incident.getServiceName() == null || !incident.getServiceName().equalsIgnoreCase(service)) {
                    return false;
                }
            }

            if (severityAtLeast != null && !severityAtLeast.isBlank()) {
                if (incident.getSeverity() == null) {
                    return false;
                }
                try {
                    Severity threshold = Severity.valueOf(severityAtLeast.trim().toUpperCase());
                    // In Severity enum: BLOCKER (0), CRITICAL (1), MAJOR (2), MINOR (3), TRIVIAL (4)
                    // Lower or equal ordinal means higher or equal severity
                    if (incident.getSeverity().ordinal() > threshold.ordinal()) {
                        return false;
                    }
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }

            return true;
        }
    }
}