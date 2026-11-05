package com.example.keepsrv.mixin;

import com.example.keepsrv.access.KeepSrvServerData;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen {

    @Shadow
    @Final
    private ServerData serverData;

    protected ManageServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void addKeepSrvCheckbox(CallbackInfo ci) {
        KeepSrvServerData ext = (KeepSrvServerData) this.serverData;

        // ipEdit 位于 (this.width / 2 - 100, 106)，宽 200，高 20
        // 复选框放在其右侧 8px 处：(this.width / 2 + 108, 108)
        Checkbox checkbox = Checkbox.builder(Component.literal("保持SRV"), this.font)
            .pos(this.width / 2 + 108, 108)
            .selected(ext.keepsrv$isKeepSrv())
            .onValueChange((box, selected) -> ext.keepsrv$setKeepSrv(selected))
            .tooltip(Tooltip.create(Component.literal("进服握手时保持使用输入的原始域名和端口（如 25565），绕过校园网对 SRV 真实端口的拦截")))
            .build();

        this.addRenderableWidget(checkbox);
    }
}
