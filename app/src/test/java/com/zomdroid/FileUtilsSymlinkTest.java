package com.zomdroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public class FileUtilsSymlinkTest {
    @Test
    public void deleteDirectoryUnlinksShadowWithoutDeletingRealMod() throws Exception {
        Path sandbox = Files.createTempDirectory("zomdroid-shadowbridge-test");
        Path realMod = Files.createDirectories(sandbox.resolve("mods/real-mod/common/media/lua"));
        Path payload = realMod.resolve("mod.lua");
        Files.write(payload, "return true\n".getBytes(StandardCharsets.UTF_8));

        Path shadowParent = Files.createDirectories(sandbox.resolve("shadow"));
        Path shadowMod = shadowParent.resolve("real-mod");
        Files.createSymbolicLink(shadowMod, sandbox.resolve("mods/real-mod"));

        assertTrue(FileUtils.deleteDirectory(shadowMod.toFile()));
        assertFalse(Files.exists(shadowMod, LinkOption.NOFOLLOW_LINKS));
        assertTrue("Deleting the ShadowBridge link must preserve the real Lua payload",
                Files.isRegularFile(payload));

        FileUtils.deleteDirectory(sandbox.toFile());
    }
}
