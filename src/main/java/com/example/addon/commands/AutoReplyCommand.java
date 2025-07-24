package com.example.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.example.addon.modules.AutoReply;
import net.minecraft.command.CommandSource;

import java.util.List;
import java.util.ArrayList;

public class AutoReplyCommand extends Command {
    public AutoReplyCommand() {
        super("autoreply", "Manage auto-reply triggers and responses.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .autoreply list - Show all trigger-reply pairs
        builder.then(literal("list").executes(context -> {
            AutoReply autoReply = Modules.get().get(AutoReply.class);
            List<String> pairs = autoReply.getTriggerReplyPairs();

            if (pairs.isEmpty()) {
                info("No trigger-reply pairs configured.");
            } else {
                info("Trigger-Reply pairs:");
                for (int i = 0; i < pairs.size(); i++) {
                    info((i + 1) + ". " + pairs.get(i));
                }
            }
            return SINGLE_SUCCESS;
        }));

        // .autoreply add "trigger" "reply" - Add new trigger-reply pair
        builder.then(literal("add")
            .then(argument("trigger", StringArgumentType.greedyString())
                .then(argument("reply", StringArgumentType.greedyString()).executes(context -> {
                    String trigger = StringArgumentType.getString(context, "trigger");
                    String reply = StringArgumentType.getString(context, "reply");

                    AutoReply autoReply = Modules.get().get(AutoReply.class);
                    List<String> pairs = new ArrayList<>(autoReply.getTriggerReplyPairs());
                    String newPair = trigger + " -> " + reply;
                    pairs.add(newPair);
                    autoReply.setTriggerReplyPairs(pairs);

                    info("Added: " + newPair);
                    return SINGLE_SUCCESS;
                }))
            )
        );

        // .autoreply remove <index> - Remove trigger-reply pair by index
        builder.then(literal("remove")
            .then(argument("index", StringArgumentType.word()).executes(context -> {
                String indexStr = StringArgumentType.getString(context, "index");
                try {
                    int index = Integer.parseInt(indexStr) - 1; // Convert to 0-based index

                    AutoReply autoReply = Modules.get().get(AutoReply.class);
                    List<String> pairs = new ArrayList<>(autoReply.getTriggerReplyPairs());

                    if (index >= 0 && index < pairs.size()) {
                        String removed = pairs.remove(index);
                        autoReply.setTriggerReplyPairs(pairs);
                        info("Removed: " + removed);
                    } else {
                        error("Invalid index. Use .autoreply list to see valid indices.");
                    }
                } catch (NumberFormatException e) {
                    error("Invalid number. Please provide a valid index.");
                }
                return SINGLE_SUCCESS;
            }))
        );

        // .autoreply clear - Clear all trigger-reply pairs
        builder.then(literal("clear").executes(context -> {
            AutoReply autoReply = Modules.get().get(AutoReply.class);
            autoReply.setTriggerReplyPairs(new ArrayList<>());
            info("Cleared all trigger-reply pairs.");
            return SINGLE_SUCCESS;
        }));

        // .autoreply status - Show module status
        builder.then(literal("status").executes(context -> {
            AutoReply autoReply = Modules.get().get(AutoReply.class);
            info("Auto-Reply status: " + (autoReply.isActive() ? "Enabled" : "Disabled"));
            info("Trigger-Reply pairs: " + autoReply.getTriggerReplyPairs().size());
            return SINGLE_SUCCESS;
        }));
    }
}
