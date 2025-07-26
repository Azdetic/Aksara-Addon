package com.example.addon.commands;

import com.example.addon.gui.AutoSellConfigScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class AutoSellConfigCommand extends Command {

    public AutoSellConfigCommand() {
        super("autosell-config", "Opens the Auto Sell configuration GUI.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.currentScreen == null) {
                mc.setScreen(new AutoSellConfigScreen(GuiThemes.get()));
            } else {
                ChatUtils.info("Please close the current screen first.");
            }

            return SINGLE_SUCCESS;
        });
    }
}
