package demo;

public class Main {

    enum Color {
        RED, BLUE
    }

    static class Acct {

        private long balance;
        private String owner;
        private double rate;
        private boolean open;
        private Color c;
        private Acct peer;
        char g;
        int[] arr;

        Acct(String owner, long b) {
            this.owner = owner;
            this.balance = b;
            if (b > 5) {
                rate = 1.5;
            } else {
                rate = 0.5;
            }
        }

        Acct(String o) {
            this(o, 0);
        }

        void dep(long x) {
            balance += x;
            open = x > 0;
        }

        void link(Acct p) {
            peer = p;
            c = Color.BLUE;
            g = 'z';
        }
    }

    static class Sav extends Acct {

        int term;

        Sav(String o) {
            super(o, 10);
            term = 3;
        }
    }

    public static void main(String[] a) throws Exception {
        Acct x = new Acct("alice", 10);
        Acct y = new Acct("bob");
        Sav s = new Sav("carol");
        x.dep(5);
        y.dep(-1);
        x.link(y);
        s.dep(7);
        x = null;
        y = null;
        System.gc();
        Thread.sleep(1200);
    }
}
