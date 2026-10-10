package com.clinicops.app.util;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Minimal RFC-4180 CSV writer. Quotes fields containing commas, quotes, or newlines;
 * doubles internal quotes. Sufficient for clinic-scale exports (no streaming hot loop concerns).
 */
public final class CsvWriter {

    private CsvWriter() {}

    public static void writeRow(Writer out, List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.write(',');
            out.write(escape(cells.get(i)));
        }
        out.write("\r\n");
    }

    public static String escape(Object value) {
        if (value == null) return "";
        String s = value.toString();
        boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuoting) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
