package com.zomdroid;

import java.util.ArrayList;
import java.util.Arrays;

/** Proves that stale ON values cannot survive an authoritative OFF launch snapshot. */
public final class OptLabLaunchContractUnit {
    public static void main(String[] args) {
        ArrayList<String> jvm = new ArrayList<>(Arrays.asList(
                "-Xmx2G",
                "-Dzomdroid.optlab.stream.wake=1",
                "-Dzomdroid.optlab.chunk.grid.load=1",
                "-Dzomdroid.optlab.fbo.frame.budget=1",
                "-Dzomdroid.optlab.render.mvp",
                "-Dzomdroid.native.pathfinding.active=1",
                "-Dunrelated.keep=1"));

        OptLabLaunchContract.clearManagedProperties(jvm);
        require(jvm.size() == 2, "managed properties were not completely removed");
        require(jvm.contains("-Xmx2G") && jvm.contains("-Dunrelated.keep=1"),
                "unrelated JVM arguments changed");

        OptLabLaunchContract.putProperty(jvm, "zomdroid.optlab.stream.wake", "0");
        OptLabLaunchContract.putProperty(jvm, "zomdroid.optlab.chunk.grid.load", "0");
        OptLabLaunchContract.putProperty(jvm, "zomdroid.native.pathfinding.active", "0");
        OptLabLaunchContract.putProperty(jvm, "zomdroid.optlab.stream.wake", "0");
        OptLabLaunchContract.requireUniqueManagedProperties(jvm);

        require("0".equals(OptLabLaunchContract.valueOf(jvm,
                "zomdroid.optlab.stream.wake")), "stream OFF missing");
        require("0".equals(OptLabLaunchContract.valueOf(jvm,
                "zomdroid.optlab.chunk.grid.load")), "chunk OFF missing");
        require(OptLabLaunchContract.valueOf(jvm,
                "zomdroid.optlab.fbo.frame.budget") == null,
                "removed FBO governor property was emitted again");
        require("0".equals(OptLabLaunchContract.valueOf(jvm,
                "zomdroid.native.pathfinding.active")), "native OFF missing");
        OptLabLaunchContract.requireValue(jvm, "zomdroid.optlab.stream.wake", "0");
        OptLabLaunchContract.requireValue(jvm, "zomdroid.optlab.chunk.grid.load", "0");
        System.out.println("OPTLAB_LAUNCH_CONTRACT_UNIT PASS stale_on=removed off=authoritative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
