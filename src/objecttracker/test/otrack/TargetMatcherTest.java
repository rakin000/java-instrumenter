package otrack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import fixture.Account;
import fixture.Savings;

class TargetMatcherTest {
    private static final ClassLoader LOADER = TargetMatcherTest.class.getClassLoader();

    private static TargetMatcher matcher(String... specs) {
        TargetMatcher m = new TargetMatcher();
        for (String s : specs) m.add(Target.parse(s));
        return m;
    }

    /** Matches the way a class being loaded is matched: from its bytes, without loading its supertypes. */
    private static boolean matchesBytes(TargetMatcher m, Class<?> c) throws IOException {
        String internal = c.getName().replace('.', '/');
        try (InputStream in = LOADER.getResourceAsStream(internal + ".class")) {
            return m.matches(LOADER, internal, in.readAllBytes());
        }
    }

    @Test
    void exactNameMatchesOnlyThatClass() throws IOException {
        TargetMatcher m = matcher("fixture.Account");
        assertTrue(m.matches(Account.class));
        assertFalse(m.matches(Savings.class));
        assertTrue(matchesBytes(m, Account.class));
        assertFalse(matchesBytes(m, Savings.class));
    }

    @Test
    void plusAlsoMatchesSubclasses() throws IOException {
        TargetMatcher m = matcher("+fixture.Account");
        assertTrue(m.matches(Savings.class));
        assertTrue(matchesBytes(m, Savings.class));
        assertFalse(m.matches(Object.class));
    }

    interface Named {}

    static class Base implements Named {}

    static class Derived extends Base {}

    @Test
    void plusMatchesImplementorsOfInterfacesAtAnyDepth() {
        TargetMatcher m = new TargetMatcher();
        m.add(new Target(Named.class.getName(), true)); // added directly: Target.parse refuses otrack.* names
        assertTrue(m.matches(Base.class));
        assertTrue(m.matches(Derived.class));
        assertFalse(m.matches(Account.class));
    }

    @Test
    void packageLessNameMatchesInAnyPackageAndAsInnermostName() throws IOException {
        TargetMatcher m = matcher("Account");
        assertTrue(m.matches(Account.class));
        assertTrue(matchesBytes(m, Account.class));
        assertTrue(m.matchedByPackageLessName("fixture/Account"));
        assertTrue(m.matches(LOADER, "a/b/Outer$Account", new byte[0]));
        assertFalse(matcher("fixture.Account").matchedByPackageLessName("fixture/Account"));
    }

    @Test
    void packageLessNameNeverMatchesJdkClasses() {
        TargetMatcher m = matcher("String", "+Object");
        assertFalse(m.matches(String.class));
        assertFalse(m.matches(Integer.class), "java.lang.Object is not a subtype target");
        assertFalse(m.matches(Account.class), "so neither is every class extending it");
    }

    @Test
    void clearForgetsAllTargets() {
        TargetMatcher m = matcher("+fixture.Account", "Savings");
        m.clear();
        assertFalse(m.matches(Account.class));
        assertFalse(m.matches(Savings.class));
    }
}
