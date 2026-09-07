package com.limelight;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final Logger LOGGER = Logger.getLogger(LimeLog.class.getName());

    // Chatty diagnostics (device dumps, polling, periodic counters) only reach logcat in debug builds
    private static volatile boolean verbose;

    public static void setVerbose(boolean enabled) {
        verbose = enabled;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void debug(String msg) {
        if (verbose) {
            LOGGER.info(msg);
        }
    }

    public static void info(String msg) {
        LOGGER.info(msg);
    }
    
    public static void warning(String msg) {
        LOGGER.warning(msg);
    }
    
    public static void severe(String msg) {
        LOGGER.severe(msg);
    }
    
    public static void setFileHandler(String fileName) throws IOException {
        LOGGER.addHandler(new FileHandler(fileName));
    }
}
