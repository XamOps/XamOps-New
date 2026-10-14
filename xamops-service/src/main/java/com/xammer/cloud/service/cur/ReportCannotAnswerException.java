package com.xammer.cloud.service.cur;

import java.util.List;

/**
 * The report is readable, and this particular question cannot be put to it.
 *
 * <p>Thrown — never swallowed into a fallback — when a query would have to name a column the
 * export does not carry. The controller maps it to {@code 422}, which is the only way a caller
 * learns it asked for something impossible rather than receiving a number computed differently
 * from the way it is labelled.
 *
 * <p>One exception type for two questions rather than a {@code CostBasisUnavailableException}
 * per question. The 422 mapping, the body shape and the log level are identical in both cases;
 * what differs is one noun phrase, so that noun phrase is a field. A second exception class
 * would be a second thing to remember to catch.
 */
public class ReportCannotAnswerException extends RuntimeException {

    /** What could not be answered, as a noun phrase for a sentence: "the amortized cost basis". */
    private final String question;

    /** Exactly which columns are absent. Named to the reader, because "unavailable" is not actionable. */
    private final List<String> missingColumns;

    public ReportCannotAnswerException(String question, List<String> missingColumns) {
        super("This report cannot answer " + question
                + " — it does not carry " + String.join(" or ", missingColumns));
        this.question = question;
        this.missingColumns = List.copyOf(missingColumns);
    }

    public String question() {
        return question;
    }

    public List<String> missingColumns() {
        return missingColumns;
    }
}
