package io.github.sajibsrs.framecrafter;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * A copy of the world's depth, taken the moment the level finishes drawing.
 * Shader packs (and the 3D HUD) may reuse or clear the main depth buffer afterwards,
 * so the DOF pass reads this snapshot instead. Registered as a texture so the post
 * chain can sample it by name ("framecrafter:scene_depth").
 */
public final class SceneDepth extends AbstractTexture {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Framecrafter.MOD_ID, "textures/effect/scene_depth.png");
	private static final SceneDepth INSTANCE = new SceneDepth();
	private static boolean registered;

	private SceneDepth() {
	}

	/** Must run before the post chain is first loaded, or the texture manager would look for a PNG. */
	public static void register(Minecraft mc) {
		if (!registered) {
			mc.getTextureManager().register(ID, INSTANCE);
			registered = true;
		}
	}

	public static void capture(Minecraft mc) {
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		GpuTexture source = main.getDepthTexture();
		if (source == null) {
			return;
		}
		INSTANCE.ensureMatches(source);
		RenderSystem.getDevice().createCommandEncoder()
			.copyTextureToTexture(source, INSTANCE.texture, 0, 0, 0, 0, 0, source.getWidth(0), source.getHeight(0));
	}

	private void ensureMatches(GpuTexture source) {
		if (this.texture != null
			&& !this.texture.isClosed()
			&& this.texture.getWidth(0) == source.getWidth(0)
			&& this.texture.getHeight(0) == source.getHeight(0)
			&& this.texture.getFormat() == source.getFormat()) {
			return;
		}
		this.releaseTextures();
		GpuDevice device = RenderSystem.getDevice();
		this.texture = device.createTexture(
			"framecrafter scene depth",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
			source.getFormat(), source.getWidth(0), source.getHeight(0), 1, 1);
		this.textureView = device.createTextureView(this.texture);
	}
}
