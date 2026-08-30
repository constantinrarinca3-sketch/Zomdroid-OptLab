package com.zomdroid;

import java.util.Collections;
import java.util.List;

/** Compile-only registry surface used by the isolated preferences unit test. */
public final class OptLabFeatureRegistry {
    public enum Category { BUILD42, EXPERIMENTAL }
    public enum Module { FBO_RENDER_CELL, RENDER_HOTPATH, MODEL_RINGBUFFER, SHADER_UNIFORMS }
    public enum Maturity { STABLE, EXPERIMENTAL, ARCHIVED }

    public enum Feature {
        DEPTH_UPLOAD("render_chunk_depth_upload", "zomdroid.optlab.render.chunk.depth.upload",
                Module.SHADER_UNIFORMS),
        DEPTH_LOOKUP("render_chunk_depth_lookup", "zomdroid.optlab.render.chunk.depth.lookup",
                Module.SHADER_UNIFORMS),
        RING_CLEAR("render_ring_empty_clear", "zomdroid.optlab.render.ring.empty.clear",
                Module.MODEL_RINGBUFFER);

        public final Category category = Category.BUILD42;
        public final String id = name();
        public final Module module;
        public final Maturity maturity = Maturity.STABLE;
        public final boolean defaultEnabled = false;
        public final boolean safeProfile = false;
        public final boolean recommendedProfile = false;
        public final boolean aggressiveProfile = true;
        public final String preferenceKey;
        public final String agentProperty;
        public final List<Feature> dependencies = Collections.emptyList();

        Feature(String preferenceKey, String agentProperty, Module module) {
            this.preferenceKey = preferenceKey;
            this.agentProperty = agentProperty;
            this.module = module;
        }

        public boolean isVisible() { return true; }
    }

    private OptLabFeatureRegistry() {}
}
