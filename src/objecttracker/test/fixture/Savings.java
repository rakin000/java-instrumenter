package fixture;

import java.util.List;

public class Savings extends Account {
    public int term;
    public List<String> tags = List.of("gold");

    public Savings(String owner) {
        super(owner, 10);
        term = 3;
    }
}
