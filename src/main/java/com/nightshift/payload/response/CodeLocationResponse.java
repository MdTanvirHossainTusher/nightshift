package com.nightshift.payload.response;

import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.enums.ConfidenceLevel;

import java.util.UUID;

public record CodeLocationResponse(
        UUID id,
        String targetRepo,
        String filePath,
        Integer startLine,
        Integer endLine,
        String frameSignature,
        ConfidenceLevel confidence,
        String snippet
) {
    public static CodeLocationResponse from(CodeLocation loc) {
        if (loc == null) return null;
        return new CodeLocationResponse(
                loc.getId(),
                loc.getTargetRepo(),
                loc.getFilePath(),
                loc.getStartLine(),
                loc.getEndLine(),
                loc.getFrameSignature(),
                loc.getConfidence(),
                loc.getSnippet()
        );
    }
}