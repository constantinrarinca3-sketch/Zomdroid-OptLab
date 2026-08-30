import com.zomdroid.agent.Main;
import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import zombie.GameProfiler;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.Styles.Style;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.ZDOptMvpFast;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.ZDOptTextureBindFast;

public final class HotPathOptimizationRuntimeUnit {
    private HotPathOptimizationRuntimeUnit() {}

    public static void main(String[] args) throws Exception {
        HotPathOptimizationRuntime.configure(true, true, true, true,
                true, true, true, true);

        Object owner = new Object();
        Object programObject = new Object();
        check(HotPathOptimizationRuntime.cachedShaderProgramById(owner, 7) == null,
                "shader miss");
        HotPathOptimizationRuntime.afterShaderProgramLookup(owner, 7, programObject);
        check(HotPathOptimizationRuntime.cachedShaderProgramById(owner, 7) == programObject,
                "shader hit");
        HotPathOptimizationRuntime.afterShaderRegistryMutation();
        check(HotPathOptimizationRuntime.cachedShaderProgramById(owner, 7) == null,
                "shader invalidation");

        GameProfiler.running = false;
        check(HotPathOptimizationRuntime.skipIdleGameProfilerArea(), "idle profiler skip");
        check(HotPathOptimizationRuntime.skipIdleProbe("Render Style"), "render probe skip");
        check(HotPathOptimizationRuntime.skipIdleProbe("IsoWorld.update"),
                "extended probe skip");
        check(!HotPathOptimizationRuntime.skipIdleProbe("not-whitelisted"),
                "probe whitelist");
        GameProfiler.running = true;
        check(!HotPathOptimizationRuntime.skipIdleGameProfilerArea(), "active profiler fallback");
        check(!HotPathOptimizationRuntime.skipIdleProbe("Render Style"),
                "active probe fallback");
        GameProfiler.running = false;

        ShaderProgram shader = new ShaderProgram();
        shader.uniform = new ShaderProgram.Uniform(3);
        check(ZDOptMvpFast.trySet(shader), "mvp unchanged handled");
        check(shader.lookups == 0 && shader.uploads == 0, "mvp unchanged no work");
        Core.getInstance().modelViewMatrixStack.value.setToken(3);
        check(ZDOptMvpFast.trySet(shader), "mvp changed handled");
        check(shader.lookups == 1 && shader.uploads == 1, "mvp one lookup/upload");

        Texture texture = new Texture(23);
        Texture.lastTextureID = 23;
        check(ZDOptTextureBindFast.tryAlreadyBound(texture, 3553), "texture safe hit");
        texture.bindAlways = true;
        check(!ZDOptTextureBindFast.tryAlreadyBound(texture, 3553),
                "texture bindAlways fallback");

        SpriteRenderer.RingBuffer ring = new SpriteRenderer.RingBuffer();
        SpriteRenderer.ringBuffer = ring;
        TextureDraw draw = new TextureDraw();
        Style style = new Style() { @Override public int getStyleID() { return 1; } };
        check(Main.StateRunTextureAdvice.enter(ring, draw, null, null,
                        null, null, null, (byte) 0, false) == 0,
                "null style must execute untouched vanilla method");
        check(zombie.core.ZDOptBuildFast.tryBuild(
                new TextureDraw[] {draw}, new Style[] {style}, 1), "build loop handled");

        Texture first = new Texture(31);
        Texture equivalent = new Texture(31);
        setField(ring, "currentTexture0", first);
        setField(ring, "currentTexture1", null);
        setField(ring, "currentTexture2", null);
        setField(ring, "currentStyle", style);
        setField(ring, "currentUseAttribArray", (byte) 0);
        Field runField = ring.getClass().getDeclaredField("currentRun");
        runField.setAccessible(true);
        Class<?> runType = runField.getType();
        Constructor<?> constructor = runType.getDeclaredConstructor();
        constructor.setAccessible(true);
        runField.set(ring, constructor.newInstance());
        check(!zombie.core.ZDOptStateRunFast.stateChanged(
                ring, draw, null, style, equivalent, null, null, (byte) 0),
                "same live gpu texture preserves run");

        HotPathOptimizationRuntime.configure(false, false, false, false,
                false, false, false, false);
        check(!ZDOptMvpFast.trySet(shader), "disabled mvp fallback");
        check(!ZDOptTextureBindFast.tryAlreadyBound(texture, 3553),
                "disabled texture fallback");
        System.out.println("HotPathOptimizationRuntimeUnit PASS");
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
