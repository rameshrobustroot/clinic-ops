package com.clinicops.app.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates the dispense quantity for a prescription line from its dosage + duration strings.
 * Conservative: returns 1 when the dosage/duration is ambiguous so the pharmacist must consciously
 * confirm rather than dispense a wrong (too-large) quantity.
 */
public final class RxQuantityHeuristic {

    // Matches 3-part (1-0-1) or 4-part M-N-E-N (1-1-0-1) dash-separated dosage strings
    private static final Pattern X_Y_Z = Pattern.compile("\\s*\\d+(?:\\s*-\\s*\\d+){1,3}\\s*");
    private static final Pattern DAYS  = Pattern.compile("(\\d+)\\s*d", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKS = Pattern.compile("(\\d+)\\s*w", Pattern.CASE_INSENSITIVE);

    public static int compute(String dosage, String duration) {
        int dosesPerDay = parseDosesPerDay(dosage);
        int days = parseDays(duration);
        if (dosesPerDay > 0 && days > 0) return dosesPerDay * days;
        return 1;
    }

    static int parseDosesPerDay(String dosage) {
        if (dosage == null || dosage.isBlank()) return 0;
        String d = dosage.trim().toUpperCase();
        switch (d) {
            case "BD":  return 2;
            case "TDS": return 3;
            case "QID": return 4;
            case "OD":  return 1;
            case "HS":  return 1;
            case "SOS":
            case "STAT": return 1;
            default: // fall through to pattern parsing
        }
        if (X_Y_Z.matcher(d).matches()) {
            int total = 0;
            for (String part : d.split("-")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                try { total += Integer.parseInt(t); } catch (NumberFormatException ignored) {}
            }
            return total;
        }
        return 0;
    }

    static int parseDays(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        // "5d", "5 days", "5 Days" …
        Matcher m = DAYS.matcher(duration);
        if (m.find()) return Integer.parseInt(m.group(1));
        // "2w", "2 weeks" …
        m = WEEKS.matcher(duration);
        if (m.find()) return Integer.parseInt(m.group(1)) * 7;
        // Plain integer — doctors commonly type just "5" meaning 5 days
        try {
            int n = Integer.parseInt(duration.trim());
            if (n > 0 && n <= 365) return n;
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private RxQuantityHeuristic() {}
}
