package com.instrumenter.example;

/**
 * Example class to be instrumented.
 * Demonstrates basic method instrumentation.
 */
public class SampleApplication {
    
    public static void main(String[] args) {
        SampleApplication app = new SampleApplication();
        app.doWork();
        app.calculateSum(5, 10);
        app.printMessage("Hello from instrumented code");
    }
    
    public void doWork() {
        System.out.println("Doing some work...");
        for (int i = 0; i < 3; i++) {
            performAction(i);
        }
    }
    
    public int calculateSum(int a, int b) {
        int result = a + b;
        System.out.println("Sum: " + result);
        return result;
    }
    
    public void printMessage(String message) {
        System.out.println("Message: " + message);
    }
    
    private void performAction(int index) {
        System.out.println("Performing action " + index);
    }
}
