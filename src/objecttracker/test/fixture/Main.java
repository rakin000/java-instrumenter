package fixture;

import java.util.ArrayList;
import java.util.List;

/** Run under the real agent by AgentEndToEndTest: allocates and updates accounts for {@code args[0]} ms. */
public class Main {
    public static void main(String[] args) throws Exception {
        long until = System.currentTimeMillis() + Long.parseLong(args[0]);
        List<Account> keep = new ArrayList<>();
        boolean ready = false;
        do {
            Account a = new Account("a");
            a.deposit(5);
            keep.add(a);
            keep.add(new Savings("s"));
            if (!ready) {
                System.out.println("ready"); // both classes are loaded now
                ready = true;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < until);
    }
}
