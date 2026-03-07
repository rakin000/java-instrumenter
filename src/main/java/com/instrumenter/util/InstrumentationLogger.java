package com.instrumenter.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.HashSet;

import org.openjdk.jol.info.GraphLayout;


public class InstrumentationLogger {
    private static final Logger logger;
    private static Instrumentation instrumentation;
    private static volatile boolean jolAvailable = true;         // set to false if JOL fails to initialize
    private static volatile boolean jammAvailable = true;        // set to false if JAMM is missing/fails
    private static volatile boolean reflectionAvailable = true;  // set to false if SecurityManager blocks field access
    private static volatile Object jammMeter;
    private static volatile Method jammMeasureDeepMethod;
   // private static final Map<Integer, Long> cumulativeSizes = new ConcurrentHashMap<>();
    
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
     * JOL will fall back to Unsafe if available; if not, we use reflection-based deep sizing.
     */
    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    private static Long measureDeepSizeWithJamm(Object obj) {
        if (!jammAvailable || obj == null) {
            return null;
        }

        try {
            if (jammMeter == null || jammMeasureDeepMethod == null) {
                synchronized (InstrumentationLogger.class) {
                    if (jammMeter == null || jammMeasureDeepMethod == null) {
                        Class<?> memoryMeterClass = Class.forName("org.github.jamm.MemoryMeter");

                        Object meter;
                        try {
                            // Preferred in recent JAMM versions
                            Method builderMethod = memoryMeterClass.getMethod("builder");
                            Object builder = builderMethod.invoke(null);
                            Method buildMethod = builder.getClass().getMethod("build");
                            meter = buildMethod.invoke(builder);
                        } catch (NoSuchMethodException noBuilder) {
                            // Backward-compatible fallback
                            meter = memoryMeterClass.getDeclaredConstructor().newInstance();
                        }

                        Method measureDeepMethod = memoryMeterClass.getMethod("measureDeep", Object.class);
                        jammMeter = meter;
                        jammMeasureDeepMethod = measureDeepMethod;
                    }
                }
            }

            Object result = jammMeasureDeepMethod.invoke(jammMeter, obj);
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
            return null;
        } catch (Throwable e) {
            jammAvailable = false;
            if (logger != null) {
                logger.warn("[OBJECT SIZE DEEP] JAMM unavailable ({}), falling back to reflection-based calculation", e.getMessage());
            }
            return null;
        }
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
                int objectId = System.identityHashCode(obj);
                logger.debug("[OBJECT SIZE] ObjectID: {} | {} bytes for instance of {}", 
                           objectId, size, obj != null ? obj.getClass().getName() : "null");
            } else if (logger != null) {
                logger.warn("[OBJECT SIZE] Instrumentation not available, cannot measure size");
            }
        } catch (Exception e) {
            System.err.println("ERROR in logObjectSize: " + e.getMessage());
        }
    }


    /**
     * Logs the deep size of an object including all its referenced objects.
     * Deep size includes the object itself plus all objects referenced through fields.
     * Uses cycle detection to avoid infinite loops with circular references.
     * 
     * @param obj the object to measure deep size for
     */
    public static void logObjectSizeDeepInstrumenter(Object obj) {
        try {
            if (logger != null && instrumentation != null) {
                long shallowSize = instrumentation.getObjectSize(obj);
                long deepSize = calculateDeepSize(obj, new HashSet<>());
                int objectId = System.identityHashCode(obj);
                logger.debug("[OBJECT SIZE DEEP] ObjectID: {} | Shallow: {} bytes | Deep: {} bytes | Type: {}", 
                           objectId, shallowSize, deepSize, obj != null ? obj.getClass().getName() : "null");
            } else if (logger != null) {
                logger.warn("[OBJECT SIZE DEEP] Instrumentation not available, cannot measure size");
            }
        } catch (Exception e) {
            System.err.println("ERROR in logObjectSizeDeep: " + e.getMessage());
        }
    }

    /**
     * Recursively calculates the deep size of an object by traversing its field references.
     * 
     * @param obj the object to measure
     * @param visited set of already visited objects (by identity hash code) to detect cycles
     * @return total size of the object and all referenced objects
     */
    private static long calculateDeepSize(Object obj, Set<Integer> visited) {
        if (obj == null) {
            return 0;
        }

        int objId = System.identityHashCode(obj);
        if (visited.contains(objId)) {
            return 0; // Already measured this object, skip to avoid cycles
        }
        visited.add(objId);

        long totalSize = 0;
        try {
            totalSize = instrumentation.getObjectSize(obj);
        } catch (Exception e) {
            if (logger != null) logger.warn("[OBJECT SIZE DEEP] Could not get shallow size: {}", e.getMessage());
            return 0;
        }

        if (!reflectionAvailable) {
            // SecurityManager previously denied field access — return shallow size only
            return totalSize;
        }

        try {
            // Traverse all fields to calculate deep size
            Class<?> clazz = obj.getClass();
            while (clazz != null) {
                Field[] fields;
                try {
                    fields = clazz.getDeclaredFields();
                } catch (SecurityException se) {
                    reflectionAvailable = false;
                    if (logger != null) logger.warn("[OBJECT SIZE DEEP] SecurityManager blocks field access, reflection-based deep size disabled: {}", se.getMessage());
                    return totalSize; // return shallow size for this object
                }

                for (Field field : fields) {
                    // Skip primitive types and static fields
                    if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object fieldValue = field.get(obj);
                        if (fieldValue != null) {
                            totalSize += calculateDeepSize(fieldValue, visited);
                        }
                    } catch (SecurityException se) {
                        reflectionAvailable = false;
                        if (logger != null) logger.warn("[OBJECT SIZE DEEP] SecurityManager blocks field access, reflection-based deep size disabled: {}", se.getMessage());
                        return totalSize;
                    } catch (IllegalAccessException e) {
                        // Skip fields that cannot be accessed
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("[OBJECT SIZE DEEP] Unexpected error during field traversal: {}", e.getMessage());
        }

        return totalSize;
    }

     public static void logObjectSizeDeep(Object obj) {
        if (logger != null) {
            if (obj == null) {
                logger.debug("[OBJECT SIZE DEEP] null object");
                return;
            }

            // if (jolAvailable) {
                try {
                    GraphLayout layout = GraphLayout.parseInstance(obj);
                    long deepSize = layout.totalSize();
                    logger.debug("[OBJECT SIZE DEEP] ObjectID: {} | Deep: {} bytes | Type: {}",
                            System.identityHashCode(obj),
                            deepSize,
                            obj.getClass().getName());
                    logger.info("[OBJECT SIZE DEEP FOOTPRINT] Object: {}#{} \n{}",
                            obj.getClass().getName(),
                            System.identityHashCode(obj),
                            layout.toFootprint()
                    );
                    return;
                } catch (Throwable e) {
                    jolAvailable = false;
                    logger.warn("[OBJECT SIZE DEEP] JOL unavailable ({}), falling back to reflection-based calculation", e.getMessage());
                }
            // }

            // Long jammSize = measureDeepSizeWithJamm(obj);
            // if (jammSize != null) {
            //     logger.debug("[OBJECT SIZE DEEP] ObjectID: {} | Deep: {} bytes | Type: {} | Provider: JAMM",
            //             System.identityHashCode(obj),
            //             jammSize,
            //             obj.getClass().getName());
            //     return;
            // }

            // // Fallback: use instrumentation-based deep size
            // logObjectSizeDeepInstrumenter(obj);
        }
    }
}
