package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.exception.XamPulseException;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses rule durations such as "5m" or "24h". Only minutes and hours are accepted. */
public final class DurationSpec {

    private static final Pattern P = Pattern.compile("^(\\d{1,4})([mh])$");

    private DurationSpec() {}

    public static Duration parse(String text, String field) {
        if (text == null) {
            throw XamPulseException.invalid("'" + field + "' is required.");
        }
        Matcher m = P.matcher(text.trim());
        if (!m.matches()) {
            throw XamPulseException.invalid("'" + field + "' must look like 5m or 2h.");
        }
        long n = Long.parseLong(m.group(1));
        return "h".equals(m.group(2)) ? Duration.ofHours(n) : Duration.ofMinutes(n);
    }
}
