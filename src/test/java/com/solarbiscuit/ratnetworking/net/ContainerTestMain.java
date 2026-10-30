package com.solarbiscuit.ratnetworking.net;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Runs the pipeline tests without Gradle (for a Linux container, where the native libraries load). */
public final class ContainerTestMain {
    private ContainerTestMain() {
    }

    public static void main(String[] args) {
        boolean bench = args.length > 0 && args[0].equals("bench");
        if (bench) {
            System.setProperty("ratnetworking.bench", "true");
        }
        LauncherDiscoveryRequest request = bench
                ? LauncherDiscoveryRequestBuilder.request().selectors(selectClass(PipelineBench.class)).build()
                : LauncherDiscoveryRequestBuilder.request()
                        .selectors(selectClass(NativePipelineTest.class), selectClass(PipelineTest.class), selectClass(VarIntsTest.class))
                        .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        summary.printFailuresTo(new java.io.PrintWriter(System.out), 20);
        System.exit(summary.getTotalFailureCount() == 0 ? 0 : 1);
    }
}
