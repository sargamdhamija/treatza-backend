import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loads KEY=VALUE pairs from a ".env" file. Real system environment
 * variables (e.g. set by a hosting platform in production) always take
 * priority over the .env file, so the same code works locally and deployed.
 */
public class Env {
    private final Map<String, String> fromFile = new HashMap<>();

    public Env(String path) {
        Path p = Paths.get(path);
        if (!Files.exists(p)) return;
        try {
            for (String line : Files.readAllLines(p)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                fromFile.put(key, value);
            }
        } catch (IOException e) {
            System.out.println("Warning: could not read " + path + " (" + e.getMessage() + ")");
        }
    }

    public String get(String key, String defaultValue) {
        String sysVal = System.getenv(key);
        if (sysVal != null && !sysVal.isEmpty()) return sysVal;
        return fromFile.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
