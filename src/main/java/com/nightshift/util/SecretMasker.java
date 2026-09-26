package com.nightshift.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strips secrets and sensitive values from log excerpts before they enter any prompt,
 * PR body, email, or {@code agent_step} row.
 *
 * <p>Configuration is loaded from {@code config/redaction.yml} (filesystem, relative to the
 * working directory) at construction time, falling back to {@code classpath:redaction.yml}
 * if the filesystem file is absent. See {@code config/redaction.yml} for the full rule set.
 *
 * <p>Two operations are applied in order:
 * <ol>
 *   <li><b>drop_lines_matching</b> — lines whose content matches any pattern in this list are
 *       removed entirely from the output.</li>
 *   <li><b>patterns</b> — each regex substitution is applied left-to-right to every surviving
 *       line.</li>
 * </ol>
 *
 * <p>Instances of this class are thread-safe after construction.
 */
@Slf4j
@Component
public class SecretMasker {

    private final List<Pattern> dropPatterns = new ArrayList<>();
    private final List<MaskRule> maskRules = new ArrayList<>();

    public SecretMasker() {
        loadConfig();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Applies masking to a single string (which may contain newlines).
     * Lines that match any {@code drop_lines_matching} pattern are removed.
     * Replacement patterns are then applied to each surviving line.
     *
     * @param text the raw text to sanitise; may be {@code null}
     * @return the sanitised text, or {@code null} if {@code text} was {@code null}
     */
    public String mask(String text) {
        if (text == null) return null;

        StringBuilder out = new StringBuilder(text.length());
        boolean first = true;
        for (String line : text.split("\n", -1)) {
            if (shouldDrop(line)) continue;
            String masked = applyReplacements(line);
            if (!first) out.append('\n');
            out.append(masked);
            first = false;
        }
        return out.toString();
    }

    // ── Configuration loading ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            Resource fsResource = new FileSystemResource("config/redaction.yml");
            Resource resource = fsResource.exists()
                    ? fsResource
                    : new ClassPathResource("redaction.yml");

            if (!resource.exists()) {
                log.warn("SecretMasker: no redaction.yml found; masking is disabled");
                return;
            }

            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> yaml = new Yaml().load(in);

                // drop_lines_matching
                List<String> drops = (List<String>) yaml.get("drop_lines_matching");
                if (drops != null) {
                    for (String raw : drops) {
                        dropPatterns.add(Pattern.compile(raw));
                    }
                }

                // patterns
                List<Map<String, String>> patterns =
                        (List<Map<String, String>>) yaml.get("patterns");
                if (patterns != null) {
                    for (Map<String, String> entry : patterns) {
                        String name        = entry.getOrDefault("name", "unnamed");
                        String regex       = entry.get("regex");
                        String replacement = entry.getOrDefault("replacement", "[REDACTED]");
                        if (regex != null) {
                            maskRules.add(new MaskRule(name, Pattern.compile(regex), replacement));
                        }
                    }
                }

                log.debug("SecretMasker: loaded {} drop patterns and {} mask rules from {}",
                        dropPatterns.size(), maskRules.size(), resource.getDescription());
            }
        } catch (Exception e) {
            log.error("SecretMasker: failed to load redaction.yml — masking disabled", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean shouldDrop(String line) {
        for (Pattern p : dropPatterns) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private String applyReplacements(String line) {
        String result = line;
        for (MaskRule rule : maskRules) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    // ── Value type ────────────────────────────────────────────────────────────

    private record MaskRule(String name, Pattern pattern, String replacement) {}
}
