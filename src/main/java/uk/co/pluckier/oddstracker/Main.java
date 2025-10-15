package uk.co.pluckier.oddstracker;

public class Main {
    public static void main(String[] args) {
        OddsTracker poller = new OddsTracker();
        poller.startPolling();
    }
}

