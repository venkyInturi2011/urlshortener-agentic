package com.example.orchestrator.resilience;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Safe-stop: once tripped, no new work is scheduled; state is preserved for inspection/resume. */
public final class SafeStop {
    private final AtomicReference<String> reason = new AtomicReference<>();
    private final Path killSwitchFile;

    public SafeStop(Path killSwitchFile) { this.killSwitchFile = killSwitchFile; }

    /** @return true if this call tripped it (first caller wins). */
    public boolean trip(String why) { return reason.compareAndSet(null, why); }

    public boolean stopped() {
        if (reason.get() == null && killSwitchFile != null && Files.exists(killSwitchFile)) trip("kill switch file present");
        return reason.get() != null;
    }

    public String reason() { return reason.get(); }
}
