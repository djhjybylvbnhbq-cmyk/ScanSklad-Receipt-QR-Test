package com.labelscanner.offline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A single saved scan result. Keeps the unit with its quantity for editing. */
public final class ResultLine {
    public static final String UNIT_PIECES = "шт.";
    public static final String UNIT_BOXES = "кор";
    public static final String UNIT_PACKAGES = "уп";

    private static final Pattern WITH_QUANTITY = Pattern.compile(
            "^(\\d+) \\((\\d+) (шт\\.|кор|уп)\\) - (.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WITHOUT_QUANTITY = Pattern.compile("^(\\d+) - (.+)$");

    public final String arrival;
    public final String quantity;
    public final String unit;
    public final String code;

    private ResultLine(String arrival, String quantity, String unit, String code) {
        this.arrival = arrival;
        this.quantity = quantity;
        this.unit = normalizeUnit(unit);
        this.code = code;
    }

    public static String normalizeUnit(String unit) {
        if (UNIT_BOXES.equals(unit) || UNIT_PACKAGES.equals(unit)) return unit;
        return UNIT_PIECES;
    }

    public static String format(String arrival, String quantity, String unit, String code) {
        if (quantity == null || quantity.trim().isEmpty()) {
            return arrival + " - " + code;
        }
        return arrival + " (" + quantity.trim() + " " + normalizeUnit(unit) + ") - " + code;
    }

    /** Parses both v1.9 legacy 'кор' lines and new 'шт.' / 'кор' / 'уп' lines. */
    public static ResultLine parse(String line) {
        if (line == null) return null;
        Matcher withQuantity = WITH_QUANTITY.matcher(line);
        if (withQuantity.matches()) {
            return new ResultLine(withQuantity.group(1), withQuantity.group(2),
                    withQuantity.group(3), withQuantity.group(4));
        }
        Matcher withoutQuantity = WITHOUT_QUANTITY.matcher(line);
        if (withoutQuantity.matches()) {
            return new ResultLine(withoutQuantity.group(1), "", UNIT_PIECES,
                    withoutQuantity.group(2));
        }
        return null;
    }
}
