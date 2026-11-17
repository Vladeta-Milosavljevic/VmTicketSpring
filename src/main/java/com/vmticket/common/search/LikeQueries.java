package com.vmticket.common.search;

public final class LikeQueries {

    private LikeQueries() {
    }

    /**
     * Contains-match pattern with {@code \}, {@code %} and {@code _} escaped for {@code LIKE ... ESCAPE '\'}.
     */
    public static String contains(String term) {
        String escaped = term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
