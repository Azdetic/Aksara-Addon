package com.example.addon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.gui.hud.ChatHud;

@Mixin(ChatHud.class)
public class ChatHudMixin {
    // Mixin tidak diperlukan lagi karena menggunakan EventHandler
    // AutoReply sekarang menggunakan ReceiveMessageEvent
}
