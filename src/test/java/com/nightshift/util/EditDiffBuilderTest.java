package com.nightshift.util;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.PatchRejectedException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditDiffBuilderTest {

    private static final Path DEMO_REPO = Path.of("demo/target-repo").toAbsolutePath();
    private static final String NAME_FORMATTER = "src/main/java/com/example/common/NameFormatter.java";

    private final EditDiffBuilder builder = new EditDiffBuilder();
    private final PatchGuard patchGuard = new PatchGuard(new NightshiftProperties());

    @Test
    void edit_producesDiffWithRealLineNumbers_thatPassesGuard() {
        String diff = builder.build(List.of(new EditDiffBuilder.Edit(NAME_FORMATTER,
                "        out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME",
                "        if (middleName != null && !middleName.isBlank()) {\n"
                        + "            out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME\n"
                        + "        }")), DEMO_REPO, 22);

        assertThat(diff).startsWith("--- a/" + NAME_FORMATTER + "\n+++ b/" + NAME_FORMATTER + "\n");
        assertThat(diff).contains("@@ -19,7 +19,9 @@");
        assertThat(diff).contains("-        out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME");

        PatchGuard.PatchAnalysis analysis = patchGuard.validate(diff, DEMO_REPO);
        assertThat(analysis.linesAdded()).isEqualTo(3);
        assertThat(analysis.linesRemoved()).isEqualTo(1);
    }

    @Test
    void edit_toleratesDedentedQuote_andReindentsReplacement() {
        String diff = builder.build(List.of(new EditDiffBuilder.Edit(NAME_FORMATTER,
                "out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME",
                "if (middleName != null) {\n    out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME\n}")),
                DEMO_REPO, 22);

        assertThat(diff).contains("+        if (middleName != null) {");
        assertThat(diff).contains("+            out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME");
        patchGuard.validate(diff, DEMO_REPO);
    }

    @Test
    void edit_withUnknownOldCode_isRejectedAsNotApplying() {
        assertThatThrownBy(() -> builder.build(List.of(new EditDiffBuilder.Edit(NAME_FORMATTER,
                "this line is not in the file", "x")), DEMO_REPO, 22))
                .isInstanceOf(PatchRejectedException.class)
                .extracting("code").isEqualTo(ErrorCodes.PATCH_DOES_NOT_APPLY);
    }

    @Test
    void realign_fixesWrongHunkHeader() {
        // Hand-written diff with a guessed line number (10) and wrong counts; the body is correct.
        String modelDiff = """
                --- a/src/main/java/com/example/common/NameFormatter.java
                +++ b/src/main/java/com/example/common/NameFormatter.java
                @@ -10,6 +10,10 @@
                         out.append(Character.toUpperCase(firstName.charAt(0)));
                -        out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME
                +        if (middleName != null) {
                +            out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME
                +        }
                         out.append(Character.toUpperCase(lastName.charAt(0)));
                """;

        String realigned = patchGuard.realign(modelDiff, DEMO_REPO);

        assertThat(realigned).contains("@@ -21,3 +21,5 @@");
        patchGuard.validate(realigned, DEMO_REPO);
    }
}
