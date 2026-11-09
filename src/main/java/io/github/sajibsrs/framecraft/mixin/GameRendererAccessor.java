package io.github.sajibsrs.framecraft.mixin;

import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("appliedPostEffects")
	List<PostChain> framecraft$appliedPostEffects();

	@Accessor("requestedPostEffects")
	List<Identifier> framecraft$requestedPostEffects();
}
