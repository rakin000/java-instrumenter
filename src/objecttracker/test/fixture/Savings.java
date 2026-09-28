package fixture;

public class Savings extends Account {
    public int term;

    public Savings(String owner) {
        super(owner, 10);
        term = 3;
    }
}
