package com.nightshift.util;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.PatchRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchGuardTest {

    private PatchGuard patchGuard;
    private final Path demoRepo = Path.of("demo/target-repo").toAbsolutePath();

    @BeforeEach
    void setUp() {
        NightshiftProperties props = new NightshiftProperties();
        patchGuard = new PatchGuard(props);
    }

    @Test
    void validDiff_passesValidation() {
        String diff = """
                --- a/src/main/java/com/example/common/NameFormatter.java
                +++ b/src/main/java/com/example/common/NameFormatter.java
                @@ -19,7 +19,9 @@
                     public static String initials(String firstName, String middleName, String lastName) {
                         StringBuilder out = new StringBuilder();
                         out.append(Character.toUpperCase(firstName.charAt(0)));
                -        out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME
                +        if (middleName != null && !middleName.isBlank()) {
                +            out.append(Character.toUpperCase(middleName.charAt(0)));
                +        }
                         out.append(Character.toUpperCase(lastName.charAt(0)));
                         return out.toString();
                     }
                """;

        PatchGuard.PatchAnalysis analysis = patchGuard.validate(diff, demoRepo);

        assertThat(analysis.filesChanged()).isEqualTo(1);
        assertThat(analysis.linesAdded()).isEqualTo(3);
        assertThat(analysis.linesRemoved()).isEqualTo(1);
        assertThat(analysis.filesTouched()).contains("src/main/java/com/example/common/NameFormatter.java");
    }

    @Test
    void touchesDeniedPath_buildGradle_throwsPatchTouchesDeniedPath() {
        String diff = """
                --- a/build.gradle
                +++ b/build.gradle
                @@ -1,3 +1,4 @@
                 plugins {
                +    id 'java'
                 }
                """;

        assertThatThrownBy(() -> patchGuard.validate(diff, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.PATCH_TOUCHES_DENIED_PATH);
                });
    }

    @Test
    void touchesDeniedPath_ciWorkflow_throwsPatchTouchesDeniedPath() {
        String diff = """
                --- a/.github/workflows/ci.yml
                +++ b/.github/workflows/ci.yml
                @@ -1,2 +1,3 @@
                 name: CI
                +run: echo
                """;

        assertThatThrownBy(() -> patchGuard.validate(diff, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.PATCH_TOUCHES_DENIED_PATH);
                });
    }

    @Test
    void touchesDeniedPath_dbMigration_throwsPatchTouchesDeniedPath() {
        String diff = """
                --- a/db/migration/V2__add_index.sql
                +++ b/db/migration/V2__add_index.sql
                @@ -1,1 +1,2 @@
                 CREATE TABLE test;
                +CREATE INDEX idx_test ON test (id);
                """;

        assertThatThrownBy(() -> patchGuard.validate(diff, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.PATCH_TOUCHES_DENIED_PATH);
                });
    }

    @Test
    void touchesTooManyFiles_throwsPatchTooLarge() {
        String diff = """
                --- a/src/main/java/com/example/A.java
                +++ b/src/main/java/com/example/A.java
                @@ -1,1 +1,1 @@
                -a
                +b
                --- a/src/main/java/com/example/B.java
                +++ b/src/main/java/com/example/B.java
                @@ -1,1 +1,1 @@
                -a
                +b
                --- a/src/main/java/com/example/C.java
                +++ b/src/main/java/com/example/C.java
                @@ -1,1 +1,1 @@
                -a
                +b
                --- a/src/main/java/com/example/D.java
                +++ b/src/main/java/com/example/D.java
                @@ -1,1 +1,1 @@
                -a
                +b
                """;

        assertThatThrownBy(() -> patchGuard.validate(diff, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.PATCH_TOO_LARGE);
                });
    }

    @Test
    void exceedsChangedLinesLimit_throwsPatchTooLarge() {
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/src/main/java/com/example/A.java\n");
        diff.append("+++ b/src/main/java/com/example/A.java\n");
        diff.append("@@ -1,1 +1,150 @@\n");
        for (int i = 0; i < 125; i++) {
            diff.append("+line ").append(i).append("\n");
        }

        assertThatThrownBy(() -> patchGuard.validate(diff.toString(), demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.PATCH_TOO_LARGE);
                });
    }

    @Test
    void doesNotApply_mismatchedHunk_throwsPatchDoesNotApply() {
        String diff = """
                --- a/src/main/java/com/example/common/NameFormatter.java
                +++ b/src/main/java/com/example/common/NameFormatter.java
                @@ -19,4 +19,4 @@
                 This content does not exist in NameFormatter!
                -Non-existent line to delete
                +Replacement line
                """;

        assertThatThrownBy(() -> patchGuard.validate(diff, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.PATCH_DOES_NOT_APPLY);
                });
    }
}