package dev.stuckfih.chess;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** User preferences plus sensible hardware-derived defaults for the engine. */
final class Settings {
    /** UCI_Elo values offered in the UI; 0 means "full strength, no limit". */
    static final int[] ELO_LEVELS = {1320, 1500, 1700, 1900, 2100, 2300, 2500, 2700, 2900, 3190, 0};
    static final int[] MOVE_TIMES_MS = {250, 500, 1000, 2000, 3000, 5000, 10000, 15000, 30000};
    static final int[] HASH_CHOICES_MB = {32, 64, 128, 256, 512};
    static final int MAX_THREADS = 8;

    private final SharedPreferences prefs;
    private final int autoThreads;
    private final int autoHashMb;

    Settings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        autoThreads = detectPerformanceCores();
        autoHashMb = detectHashMb(context);
    }

    SharedPreferences raw() {
        return prefs;
    }

    // ------------------------------------------------------------------ stored values

    int eloIndex() {
        int i = prefs.getInt("elo_index", ELO_LEVELS.length - 1);
        return i >= 0 && i < ELO_LEVELS.length ? i : ELO_LEVELS.length - 1;
    }

    void setEloIndex(int i) {
        prefs.edit().putInt("elo_index", i).apply();
    }

    int elo() {
        return ELO_LEVELS[eloIndex()];
    }

    int moveTimeIndex() {
        int i = prefs.getInt("time_index", 3);
        return i >= 0 && i < MOVE_TIMES_MS.length ? i : 3;
    }

    void setMoveTimeIndex(int i) {
        prefs.edit().putInt("time_index", i).apply();
    }

    int moveTimeMs() {
        return MOVE_TIMES_MS[moveTimeIndex()];
    }

    /** 0 = automatic. */
    int threadsSetting() {
        return prefs.getInt("threads", 0);
    }

    void setThreadsSetting(int t) {
        prefs.edit().putInt("threads", t).apply();
    }

    int threads() {
        int t = threadsSetting();
        return t > 0 ? Math.min(t, MAX_THREADS) : autoThreads;
    }

    int autoThreads() {
        return autoThreads;
    }

    /** 0 = automatic. */
    int hashSetting() {
        return prefs.getInt("hash_mb", 0);
    }

    void setHashSetting(int mb) {
        prefs.edit().putInt("hash_mb", mb).apply();
    }

    int hashMb() {
        int h = hashSetting();
        return h > 0 ? h : autoHashMb;
    }

    int autoHashMb() {
        return autoHashMb;
    }

    boolean showEval() {
        return prefs.getBoolean("show_eval", true);
    }

    void setShowEval(boolean v) {
        prefs.edit().putBoolean("show_eval", v).apply();
    }

    // ------------------------------------------------------------------ labels

    static String eloLabel(int index) {
        int e = ELO_LEVELS[index];
        if (e == 0) return "Maximum (full strength)";
        String grade = e < 1600 ? "beginner" : e < 2000 ? "club player" : e < 2400 ? "advanced"
                : e < 2800 ? "master" : "grandmaster";
        return e + " Elo (" + grade + ")";
    }

    static String timeLabel(int ms) {
        if (ms < 1000) return String.format(Locale.US, "%.2f s", ms / 1000.0);
        return (ms / 1000) + " s";
    }

    // ------------------------------------------------------------------ hardware defaults

    /**
     * Counts the fast cores ("prime" and "big" in a big.LITTLE phone). Search threads on the slow
     * cores add little strength but heat the phone, so by default only fast cores are used.
     */
    static int detectPerformanceCores() {
        int n = Math.max(1, Runtime.getRuntime().availableProcessors());
        long[] freq = new long[n];
        long max = 0;
        for (int i = 0; i < n; i++) {
            freq[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            max = Math.max(max, freq[i]);
        }
        int fast = 0;
        if (max > 0) {
            for (int i = 0; i < n; i++) if (freq[i] >= max * 0.75) fast++;
        }
        if (fast < 1) fast = Math.max(1, n / 2);
        return Math.min(fast, MAX_THREADS);
    }

    static int detectHashMb(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalMb = mi.totalMem / (1024 * 1024);
            if (totalMb >= 6000) return 256;
            if (totalMb >= 3000) return 128;
            return 64;
        } catch (RuntimeException e) {
            return 64;
        }
    }

    private static long readLong(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            try {
                String line = r.readLine();
                return line == null ? 0 : Long.parseLong(line.trim());
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
