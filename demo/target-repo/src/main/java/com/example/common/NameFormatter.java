package com.example.common;

/**
 * Shared formatting helper. Used by more than one service, which is the point of
 * demo defect #2/#8: the same NullPointerException surfaces from two different
 * services and must collapse into ONE incident, not two.
 */
public final class NameFormatter {

    private NameFormatter() {
    }

    /**
     * Builds the initials shown on a printed card.
     *
     * <p>Seeded defect: {@code middleName} is optional in the schema but treated as
     * mandatory here. Any record without one throws.
     */
    public static String initials(String firstName, String middleName, String lastName) {
        StringBuilder out = new StringBuilder();
        out.append(Character.toUpperCase(firstName.charAt(0)));
        out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME
        out.append(Character.toUpperCase(lastName.charAt(0)));
        return out.toString();
    }

    public static String displayName(String firstName, String lastName) {
        return (firstName + " " + lastName).trim();
    }
}
