package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.commands.AutoReplyCommand;
import com.example.addon.commands.AutoSellConfigCommand;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.ModuleExample;
import com.example.addon.modules.AutoReply;
import com.example.addon.modules.AutoExpBottle;
import com.example.addon.modules.AutoSell;
import com.example.addon.modules.AutoFeed;
import com.example.addon.modules.AutoHeal;
import com.example.addon.modules.AutoDrop;
import com.example.addon.modules.AntiStaff;
import com.example.addon.modules.AutoEatPlus;
import com.example.addon.modules.AutoBuy;
import com.example.addon.modules.AutoFarmPlus;
import com.example.addon.modules.BowSpamPlus;
import com.example.addon.modules.AutoTriggerPlus;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Aksara");
    public static final HudGroup HUD_GROUP = new HudGroup("Aksara");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Aksara Addons by Ratih");

        // Modules
        Modules.get().add(new ModuleExample());
        Modules.get().add(new AutoReply());
        Modules.get().add(new AutoExpBottle());
        Modules.get().add(new AutoSell());
        Modules.get().add(new AutoFeed());
        Modules.get().add(new AutoHeal());
        Modules.get().add(new AutoDrop());
        Modules.get().add(new AntiStaff());
        Modules.get().add(new AutoEatPlus());
        Modules.get().add(new AutoBuy());
        Modules.get().add(new AutoFarmPlus());
        Modules.get().add(new BowSpamPlus());
        Modules.get().add(new AutoTriggerPlus());

        // Commands
        Commands.add(new CommandExample());
        Commands.add(new AutoReplyCommand());
        Commands.add(new AutoSellConfigCommand());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Ratih", "aksara-addons");
    }
}
