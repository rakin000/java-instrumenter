package fixture;

/** Tracked by the tests. Outside {@code otrack.*} because the agent never instruments its own packages. */
public class Account {
    public long balance;
    public String owner;

    public Account(String owner, long balance) {
        this.owner = owner;
        this.balance = balance;
    }

    public Account(String owner) {
        this(owner, 0); // delegates, so it must not count the object a second time
    }

    public void deposit(long amount) {
        balance += amount;
    }
}
