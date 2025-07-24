package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AutoReply extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgProfiles = this.settings.createGroup("Profiles");

    // General Settings
    private final Setting<Boolean> useBaseDelay = sgGeneral.add(new BoolSetting.Builder()
        .name("use-base-delay")
        .description("Enable base delay (fixed delay in milliseconds).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Base delay in milliseconds before sending reply.")
        .defaultValue(1000)
        .min(0)
        .max(10000)
        .sliderMax(5000)
        .visible(() -> useBaseDelay.get())
        .build()
    );

    private final Setting<Boolean> useRandomDelay = sgGeneral.add(new BoolSetting.Builder()
        .name("use-random-delay")
        .description("Enable random delay (variable delay in seconds).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> randomDelayMin = sgGeneral.add(new IntSetting.Builder()
        .name("random-delay-min")
        .description("Minimum random delay in seconds.")
        .defaultValue(1)
        .min(0)
        .max(10)
        .sliderMax(10)
        .visible(() -> useRandomDelay.get())
        .build()
    );

    private final Setting<Integer> randomDelayMax = sgGeneral.add(new IntSetting.Builder()
        .name("random-delay-max")
        .description("Maximum random delay in seconds.")
        .defaultValue(3)
        .min(0)
        .max(10)
        .sliderMax(10)
        .visible(() -> useRandomDelay.get())
        .build()
    );

    private final Setting<Boolean> caseSensitive = sgGeneral.add(new BoolSetting.Builder()
        .name("case-sensitive")
        .description("Whether trigger matching should be case sensitive.")
        .defaultValue(false)
        .build()
    );

    // Dynamic Trigger-Reply pairs system
    private final Setting<List<String>> triggerReplyPairs = sgProfiles.add(new StringListSetting.Builder()
        .name("trigger-reply-pairs")
        .description("Format: 'Trigger -> Reply'. Example: 'hello -> Hi there!'. Click + to add more pairs.")
        .defaultValue(List.of("ketik kata ini: Aksara -> Aksara"))
        .build()
    );

    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();

    public AutoReply() {
        super(AddonTemplate.CATEGORY, "auto-reply", "Automatically responds to chat messages with custom triggers and replies.");
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;

        String message = event.getMessage().getString();

        // Skip our own messages to prevent loops
        if (message.startsWith("<") && message.contains(">")) return;

        List<String> pairs = triggerReplyPairs.get();

        // Check each trigger-reply pair
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;

            // Split by -> separator
            String[] parts = pair.split(" -> ", 2);
            if (parts.length != 2) continue;

            String triggerText = parts[0].trim();
            String replyText = parts[1].trim();

            // Skip empty triggers or replies
            if (triggerText.isEmpty() || replyText.isEmpty()) continue;

            // Check if message matches trigger
            boolean matches = caseSensitive.get()
                ? message.contains(triggerText)
                : message.toLowerCase().contains(triggerText.toLowerCase());

            if (matches) {
                // Schedule reply with delay
                executeReply(replyText, triggerText);
                break; // Only reply to first matching trigger
            }
        }
    }

    private void executeReply(String replyText, String triggerText) {
        int totalDelay = 0;

        // Use base delay if enabled
        if (useBaseDelay.get()) {
            totalDelay += delay.get();
        }

        // Use random delay if enabled
        if (useRandomDelay.get()) {
            int minRandomDelay = randomDelayMin.get() * 1000; // Convert seconds to milliseconds
            int maxRandomDelay = randomDelayMax.get() * 1000; // Convert seconds to milliseconds

            // Generate random delay between min and max (inclusive)
            int randomDelay = 0;
            if (maxRandomDelay > minRandomDelay) {
                randomDelay = random.nextInt(maxRandomDelay - minRandomDelay + 1) + minRandomDelay;
            } else if (minRandomDelay > 0) {
                randomDelay = minRandomDelay;
            }

            totalDelay += randomDelay;
        }

        // If both are disabled, send immediately
        if (totalDelay > 0) {
            // Check scheduler validity before scheduling
            if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }
            scheduler.schedule(() -> sendReply(replyText, triggerText), totalDelay, TimeUnit.MILLISECONDS);
        } else {
            sendReply(replyText, triggerText);
        }
    }

    private void sendReply(String replyText, String triggerText) {
        if (mc.player != null) {
            ChatUtils.sendPlayerMsg(replyText);
            AddonTemplate.LOG.info("Auto replied to '" + triggerText + "' with: " + replyText);
        }
    }

    @Override
    public void onDeactivate() {
        // Shutdown scheduler when module is deactivated
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    @Override
    public void onActivate() {
        // Re-create scheduler when module is activated
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
    }

    // Public methods for command access
    public List<String> getTriggerReplyPairs() {
        return triggerReplyPairs.get();
    }

    public void setTriggerReplyPairs(List<String> pairs) {
        triggerReplyPairs.set(pairs);
    }
}
