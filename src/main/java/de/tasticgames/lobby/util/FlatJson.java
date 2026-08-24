package de.tasticgames.lobby.util;

import java.util.Map;

/**
 * The payload format of the network command bus: a flat JSON object of string values.
 * The proxy reads it back as {@code Map<String, String>}, so nested values do not exist.
 */
public final class FlatJson {

    private FlatJson() {
    }

    public static String encode(Map<String, String> payload) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
