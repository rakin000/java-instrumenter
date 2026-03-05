package com.instrumenter.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

public class InstrumentationLogger {
    private static final Logger logger;
    private static Instrumentation instrumentation;
    
    static {
        Logger l = null;
        try {
            l = LoggerFactory.getLogger("Instrumentation");
        } catch (Exception e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
        }
        logger = l;
    }
    
    /**
     * Set the Instrumentation instance (called by the agent).
     */
    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }
    
    public static void logEntry(String message) {
        try {
            if (logger != null) {
                logger.debug(message);
            }
        } catch (Exception e) {
            System.err.println("ERROR in logEntry: " + e.getMessage());
        }
    }
    
    public static void logExit(String message) {
        try {
            if (logger != null) {
                logger.debug(message);
            }
        } catch (Exception e) {
            System.err.println("ERROR in logExit: " + e.getMessage());
        }
    }
    
    public static void logFieldAccess(String message) {
        try {
            if (logger != null) {
                logger.debug(message);
            }
        } catch (Exception e) {
            System.err.println("ERROR in logFieldAccess: " + e.getMessage());
        }
    }
    
    public static void logObjectSize(Object obj) {
        try {
            if (logger != null && instrumentation != null) {
                long size = instrumentation.getObjectSize(obj);
                logger.debug("[OBJECT SIZE] {} bytes for instance of {}", size, 
                           obj != null ? obj.getClass().getName() : "null");
            } else if (logger != null) {
                logger.warn("[OBJECT SIZE] Instrumentation not available, cannot measure size");
            }
        } catch (Exception e) {
            System.err.println("ERROR in logObjectSize: " + e.getMessage());
        }
    }
}
