package com.example.orchestrator.validate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a real Maven goal (compile / test) in the target project. Skipped (and recorded as such) with --skip-build. */
public final class MavenValidator implements Validator {
    private final String goal;
    private final String name;
    private final long timeoutMinutes;

    public MavenValidator(String name, String goal, long timeoutMinutes) {
        this.name = name;
        this.goal = goal;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Override
    public String name() { return name; }

    @Override
    public Result validate(Context ctx) {
        if (ctx.skipBuild()) return Result.skipped("SKIPPED (--skip-build): mvn " + goal + " not executed");
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) cmd.addAll(List.of("cmd.exe", "/c"));
        cmd.addAll(List.of("mvn", "-B", "-q", "-f", ctx.targetDir().resolve("pom.xml").toString(), goal));
        try {
            Files.createDirectories(ctx.logDir());
            Path log = ctx.logDir().resolve(ctx.node().id + "-mvn-" + goal + ".log");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return Result.fail("mvn " + goal + " timed out after " + timeoutMinutes + " min");
            }
            if (p.exitValue() == 0) return Result.pass("mvn " + goal + " succeeded (log: " + log.getFileName() + ")");
            List<String> lines = Files.readAllLines(log);
            String tail = String.join(" | ", lines.subList(Math.max(0, lines.size() - 12), lines.size()));
            return Result.fail("mvn " + goal + " failed: " + tail);
        } catch (IOException e) {
            return Result.fail("could not run mvn: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("interrupted");
        }
    }
}
