package com.hxpig.fundvaluation;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class FundFormat {
    static final String BLANK = "---";

    private FundFormat() {
    }

    static String orBlank(String value) {
        if (!hasValue(value)) {
            return BLANK;
        }
        return value.trim();
    }

    static String percent(String value) {
        Double number = parseNumber(value);
        if (number == null) {
            return BLANK;
        }
        return percentFromNumber(number);
    }

    static String value4(String value) {
        Double number = parseNumber(value);
        if (number == null) {
            return BLANK;
        }
        return String.format(Locale.CHINA, "%.4f", number);
    }

    static String percentFromNumber(double value) {
        return String.format(Locale.CHINA, "%.2f%%", value);
    }

    static Double parseNumber(String value) {
        if (!hasValue(value)) {
            return null;
        }
        String normalized = value.trim()
                .replace(",", "")
                .replace("%", "");
        if (normalized.length() == 0 || "nan".equalsIgnoreCase(normalized)) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean hasValue(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        return text.length() > 0
                && !BLANK.equals(text)
                && !"None".equalsIgnoreCase(text)
                && !"null".equalsIgnoreCase(text)
                && !"nan".equalsIgnoreCase(text);
    }

    static String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
