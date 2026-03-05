package com.instrumenter.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstrumentationLogger {
    private static final Logger logger = LoggerFactory.getLogger("Instrumentation");
    
    public static void logEntry(String message) {
        logger.info(message);
    }
    
    public static void logExit(String message) {
        logger.info(message);
    }
    
    public static void logFieldAccess(String message) {
        logger.info(message);
    }
}
