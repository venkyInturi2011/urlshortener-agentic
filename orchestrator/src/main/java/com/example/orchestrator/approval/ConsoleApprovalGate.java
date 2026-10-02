package com.example.orchestrator.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

/** Interactive approval on the console. EOF or anything other than y/yes is a denial (fail closed). */
public final class ConsoleApprovalGate implements ApprovalGate {
    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleApprovalGate(java.io.InputStream in, PrintStream out) {
        this.in = new BufferedReader(new InputStreamReader(in));
        this.out = out;
    }

    @Override
    public synchronized Decision request(Request r) {
        out.println();
        out.println("=== APPROVAL REQUIRED: " + r.nodeId() + " ===");
        out.println("Reason : " + r.reason());
        out.println(r.summary());
        if (!r.highImpactPaths().isEmpty()) out.println("High-impact changes: " + r.highImpactPaths());
        out.print("Approve? [y/N] ");
        out.flush();
        try {
            String line = in.readLine();
            boolean ok = line != null && (line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes"));
            return new Decision(ok, System.getProperty("user.name", "human"), "console: " + line);
        } catch (IOException e) {
            return new Decision(false, "human", "input error: " + e.getMessage());
        }
    }
}
