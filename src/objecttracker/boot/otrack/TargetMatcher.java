package otrack;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;

/**
 * Decides which classes are targets. A class matches if its name is a target, if its package-less name is
 * (see {@link #simpleHit}), or, for {@code +} targets, if any of its supertypes matches. All names here are
 * internal ({@code a/B$C}). JDK classes never match a package-less or subtype target.
 */
final class TargetMatcher {
    private static final String[] JDK_PREFIXES = {"java/", "javax/", "jdk/", "sun/", "com/sun/"};

    private final Set<String> exact = ConcurrentHashMap.newKeySet();
    private final Set<String> subtypes = ConcurrentHashMap.newKeySet();       // incl. their subtypes
    private final Set<String> simple = ConcurrentHashMap.newKeySet();         // package-less, match in any package
    private final Set<String> simpleSubtypes = ConcurrentHashMap.newKeySet(); // same, incl. subtypes
    private final Map<String, Boolean> headerCache = new ConcurrentHashMap<>();

    static boolean isJdk(String internal) {
        for (String p : JDK_PREFIXES) if (internal.startsWith(p)) return true;
        return false;
    }

    void add(Target t) {
        String n = t.name().replace('.', '/');
        exact.add(n);
        if (t.withSubtypes()) subtypes.add(n);
        if (n.indexOf('/') < 0) { // no package given: also resolve against any package at load time
            simple.add(n);
            if (t.withSubtypes()) simpleSubtypes.add(n);
        }
        headerCache.clear();
    }

    void clear() {
        exact.clear();
        subtypes.clear();
        simple.clear();
        simpleSubtypes.clear();
        headerCache.clear();
    }

    /** True if {@code internal} matched only through a package-less target, not by its full name. */
    boolean matchedByPackageLessName(String internal) {
        return !exact.contains(internal) && simpleHit(simple, internal);
    }

    /** Match for an already loaded class, using reflection (no bytes needed). */
    boolean matches(Class<?> c) {
        String n = c.getName().replace('.', '/');
        if (byName(n)) return true;
        return hasSubtypeTargets() && inHierarchy(c, 0);
    }

    /** Match for a class being loaded, walking its supertypes through {@code loader} without loading them. */
    boolean matches(ClassLoader loader, String internal, byte[] bytes) {
        if (byName(internal)) return true;
        return hasSubtypeTargets() && headerMatches(loader, new ClassReader(bytes));
    }

    private boolean byName(String internal) {
        return exact.contains(internal) || simpleHit(simple, internal);
    }

    private boolean isSubtypeTarget(String internal) {
        return subtypes.contains(internal) || simpleHit(simpleSubtypes, internal);
    }

    private boolean hasSubtypeTargets() {
        return !subtypes.isEmpty() || !simpleSubtypes.isEmpty();
    }

    /**
     * True if the class part of {@code internal} equals an entry of {@code names}, either whole
     * ({@code Outer$Inner}) or as the innermost nested name ({@code Inner}). JDK types never match.
     */
    private static boolean simpleHit(Set<String> names, String internal) {
        if (names.isEmpty() || isJdk(internal)) return false;
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        if (names.contains(s)) return true;
        int d = s.lastIndexOf('$');
        return d >= 0 && d + 1 < s.length() && names.contains(s.substring(d + 1));
    }

    private boolean inHierarchy(Class<?> c, int depth) {
        if (c == null || depth > 64) return false;
        if (isSubtypeTarget(c.getName().replace('.', '/'))) return true;
        if (inHierarchy(c.getSuperclass(), depth + 1)) return true;
        for (Class<?> i : c.getInterfaces()) if (inHierarchy(i, depth + 1)) return true;
        return false;
    }

    private boolean headerMatches(ClassLoader loader, ClassReader cr) {
        if (superMatches(loader, cr.getSuperName(), 0)) return true;
        for (String i : cr.getInterfaces()) if (superMatches(loader, i, 0)) return true;
        return false;
    }

    /** Walks supertypes by reading .class resources, never loading classes. JDK types are not traversed. */
    private boolean superMatches(ClassLoader loader, String n, int depth) {
        if (n == null || depth > 64) return false;
        if (isSubtypeTarget(n)) return true;
        if (isJdk(n)) return false;
        Boolean cached = headerCache.get(n);
        if (cached != null) return cached;
        boolean r = false;
        try (InputStream in = loader.getResourceAsStream(n + ".class")) {
            if (in != null) r = headerMatches(loader, new ClassReader(in));
        } catch (Throwable ignored) {
        }
        headerCache.put(n, r);
        return r;
    }
}
