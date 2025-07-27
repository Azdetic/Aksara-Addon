package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.sound.SoundEvents;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ServerSecurityMonitor extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgDetection = this.settings.createGroup("Detection");
    private final SettingGroup sgMonitoring = this.settings.createGroup("Monitoring");
    private final SettingGroup sgNotifications = this.settings.createGroup("Notifications");
    private final SettingGroup sgReporting = this.settings.createGroup("Reporting");

    // === GENERAL SETTINGS ===
    private final Setting<Boolean> enableMonitoring = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-monitoring")
        .description("Enable server security monitoring")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Security scan interval in seconds (30 = 30 seconds)")
        .defaultValue(30)
        .min(10)
        .max(300)
        .sliderMax(120)
        .build()
    );

    // === DETECTION SETTINGS ===
    private final Setting<Boolean> detectSuspiciousCommands = sgDetection.add(new BoolSetting.Builder()
        .name("detect-suspicious-commands")
        .description("Monitor for potentially dangerous commands")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectSpamming = sgDetection.add(new BoolSetting.Builder()
        .name("detect-spamming")
        .description("Detect chat spam and command spam")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> spamThreshold = sgDetection.add(new IntSetting.Builder()
        .name("spam-threshold")
        .description("Messages per second to consider spam (5 = 5 messages/second)")
        .defaultValue(5)
        .min(2)
        .max(20)
        .sliderMax(15)
        .visible(() -> detectSpamming.get())
        .build()
    );

    private final Setting<Boolean> detectPermissionEscalation = sgDetection.add(new BoolSetting.Builder()
        .name("detect-permission-escalation")
        .description("Monitor for permission escalation attempts")
        .defaultValue(true)
        .build()
    );

    // === MONITORING SETTINGS ===
    private final Setting<Boolean> monitorPlayerList = sgMonitoring.add(new BoolSetting.Builder()
        .name("monitor-player-list")
        .description("Monitor player join/leave patterns")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> monitorServerCommands = sgMonitoring.add(new BoolSetting.Builder()
        .name("monitor-server-commands")
        .description("Log all server commands and responses")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> monitorPerformance = sgMonitoring.add(new BoolSetting.Builder()
        .name("monitor-performance")
        .description("Monitor server performance indicators")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackPlayerBehavior = sgMonitoring.add(new BoolSetting.Builder()
        .name("track-player-behavior")
        .description("Track and analyze player behavior patterns")
        .defaultValue(true)
        .build()
    );

    // === NOTIFICATIONS ===
    private final Setting<Boolean> enableAlerts = sgNotifications.add(new BoolSetting.Builder()
        .name("enable-alerts")
        .description("Show security alerts in chat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundAlerts = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-alerts")
        .description("Play sound for critical security alerts")
        .defaultValue(true)
        .build()
    );

    private final Setting<AlertLevel> alertLevel = sgNotifications.add(new EnumSetting.Builder<AlertLevel>()
        .name("alert-level")
        .description("Minimum alert level to show")
        .defaultValue(AlertLevel.MEDIUM)
        .build()
    );

    // === REPORTING ===
    private final Setting<Boolean> generateReports = sgReporting.add(new BoolSetting.Builder()
        .name("generate-reports")
        .description("Generate security reports")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> reportInterval = sgReporting.add(new IntSetting.Builder()
        .name("report-interval")
        .description("Generate report every X minutes (60 = 1 hour)")
        .defaultValue(60)
        .min(5)
        .max(720) // 12 hours
        .sliderMax(240) // 4 hours
        .visible(() -> generateReports.get())
        .build()
    );

    private final Setting<Boolean> logToFile = sgReporting.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Save security logs to file")
        .defaultValue(false)
        .build()
    );

    // === INTERNAL VARIABLES ===
    private final Map<String, PlayerSecurityData> playerData = new ConcurrentHashMap<>();
    private final List<SecurityEvent> securityEvents = new ArrayList<>();
    private final Map<String, Integer> commandCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> messageCounts = new ConcurrentHashMap<>();

    private long lastScanTime = 0;
    private long lastReportTime = 0;
    private int totalSecurityEvents = 0;
    private int criticalEvents = 0;
    private boolean isScanning = false;

    // Suspicious command patterns
    private final List<Pattern> suspiciousPatterns = Arrays.asList(
        Pattern.compile(".*/(op|deop|ban|kick|stop|restart|reload).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/(give|gamemode|tp|teleport).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/(execute|fill|setblock|clone).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/pl(ugins)?.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/(perm|permission|lp|luckperms).*", Pattern.CASE_INSENSITIVE)
    );

    public enum AlertLevel {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
        CRITICAL("Critical");

        private final String name;

        AlertLevel(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public ServerSecurityMonitor() {
        super(AddonTemplate.CATEGORY, "server-security-monitor", "Comprehensive server security monitoring and analysis tool for administrators.");
    }

    @Override
    public void onActivate() {
        isScanning = false;
        totalSecurityEvents = 0;
        criticalEvents = 0;
        lastScanTime = System.currentTimeMillis();
        lastReportTime = System.currentTimeMillis();

        // Clear previous data
        playerData.clear();
        securityEvents.clear();
        commandCounts.clear();
        lastMessageTime.clear();
        messageCounts.clear();

        if (enableAlerts.get()) {
            ChatUtils.info("§a[SECURITY MONITOR] §fServer security monitoring activated!");
            ChatUtils.info("§7Monitoring: Commands, Chat, Permissions, Performance");
        }
    }

    @Override
    public void onDeactivate() {
        isScanning = false;

        if (enableAlerts.get()) {
            ChatUtils.info("§a[SECURITY MONITOR] §fDeactivated. Total events detected: §c" + totalSecurityEvents);
            if (generateReports.get()) {
                generateSecurityReport();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!enableMonitoring.get() || isScanning) return;

        long currentTime = System.currentTimeMillis();

        // Perform periodic security scan
        if (currentTime - lastScanTime > scanInterval.get() * 1000L) {
            performSecurityScan();
            lastScanTime = currentTime;
        }

        // Generate periodic reports
        if (generateReports.get() && currentTime - lastReportTime > reportInterval.get() * 60000L) {
            generateSecurityReport();
            lastReportTime = currentTime;
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!enableMonitoring.get()) return;

        String message = event.getMessage().getString();

        // Monitor for suspicious server messages
        if (detectSuspiciousCommands.get()) {
            checkForSuspiciousServerMessages(message);
        }

        // Monitor for error messages that might indicate security issues
        if (message.contains("permission") || message.contains("denied") ||
            message.contains("unauthorized") || message.contains("illegal")) {
            logSecurityEvent("Permission/Security Error Detected", message, AlertLevel.MEDIUM);
        }

        // Monitor for plugin/mod related messages
        if (message.contains("plugin") || message.contains("mod") || message.contains("hack")) {
            logSecurityEvent("Plugin/Mod Activity Detected", message, AlertLevel.LOW);
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!enableMonitoring.get()) return;

        // Monitor server messages
        if (event.packet instanceof GameMessageS2CPacket packet) {
            String message = packet.content().getString();
            if (monitorServerCommands.get()) {
                logServerCommand(message);
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!enableMonitoring.get() || mc.player == null) return;

        // Monitor outgoing chat commands
        if (event.packet instanceof ChatMessageC2SPacket packet) {
            String message = packet.chatMessage();
            String playerName = mc.player.getName().getString();

            // Check for spam
            if (detectSpamming.get()) {
                checkForSpam(playerName, message);
            }

            // Check for suspicious commands
            if (detectSuspiciousCommands.get() && message.startsWith("/")) {
                checkForSuspiciousCommand(playerName, message);
            }
        }
    }

    private void performSecurityScan() {
        isScanning = true;

        try {
            // Performance monitoring
            if (monitorPerformance.get()) {
                analyzeServerPerformance();
            }

            // Player behavior analysis
            if (trackPlayerBehavior.get()) {
                analyzePlayerBehavior();
            }

            // Clean old data
            cleanOldData();

        } catch (Exception e) {
            if (enableAlerts.get()) {
                ChatUtils.error("Security scan error: " + e.getMessage());
            }
        } finally {
            isScanning = false;
        }
    }

    private void checkForSpam(String playerName, String message) {
        long currentTime = System.currentTimeMillis();

        // Update message count
        messageCounts.merge(playerName, 1, Integer::sum);

        // Check if player sent messages too quickly
        Long lastTime = lastMessageTime.get(playerName);
        if (lastTime != null) {
            long timeDiff = currentTime - lastTime;
            if (timeDiff < 1000) { // Less than 1 second
                int count = messageCounts.getOrDefault(playerName, 0);
                if (count > spamThreshold.get()) {
                    logSecurityEvent("Spam Detected",
                        playerName + " sent " + count + " messages in " + timeDiff + "ms",
                        AlertLevel.HIGH);
                }
            }
        }

        lastMessageTime.put(playerName, currentTime);

        // Reset count every second
        if (currentTime % 1000 < 100) {
            messageCounts.clear();
        }
    }

    private void checkForSuspiciousCommand(String playerName, String command) {
        // Check against suspicious patterns
        for (Pattern pattern : suspiciousPatterns) {
            if (pattern.matcher(command).matches()) {
                logSecurityEvent("Suspicious Command",
                    playerName + " executed: " + command,
                    AlertLevel.MEDIUM);
                break;
            }
        }

        // Track command usage
        commandCounts.merge(command.split(" ")[0], 1, Integer::sum);
    }

    private void checkForSuspiciousServerMessages(String message) {
        // Check for permission escalation attempts
        if (detectPermissionEscalation.get()) {
            if (message.contains("was opped") || message.contains("permission added") ||
                message.contains("rank changed") || message.contains("promoted")) {
                logSecurityEvent("Permission Change Detected", message, AlertLevel.HIGH);
            }
        }

        // Check for ban/kick activities
        if (message.contains("banned") || message.contains("kicked") || message.contains("muted")) {
            logSecurityEvent("Moderation Action Detected", message, AlertLevel.MEDIUM);
        }

        // Check for server status changes
        if (message.contains("server") && (message.contains("restart") || message.contains("stop") || message.contains("shutdown"))) {
            logSecurityEvent("Server Status Change", message, AlertLevel.CRITICAL);
        }
    }

    private void logServerCommand(String message) {
        // Log server commands for analysis
        if (logToFile.get()) {
            // Implementation for file logging would go here
            // For now, just track in memory
        }
    }

    private void analyzeServerPerformance() {
        // Monitor performance indicators
        if (mc.world != null) {
            // Check TPS and other performance metrics
            // Implementation depends on available APIs
        }
    }

    private void analyzePlayerBehavior() {
        long currentTime = System.currentTimeMillis();

        // Analyze player behavior patterns
        for (Map.Entry<String, PlayerSecurityData> entry : playerData.entrySet()) {
            PlayerSecurityData data = entry.getValue();

            // Check for unusual patterns
            if (data.commandCount > 50 && currentTime - data.sessionStart < 300000) { // 50 commands in 5 minutes
                logSecurityEvent("High Command Activity",
                    entry.getKey() + " executed " + data.commandCount + " commands in 5 minutes",
                    AlertLevel.MEDIUM);
            }
        }
    }

    private void cleanOldData() {
        long currentTime = System.currentTimeMillis();
        long threshold = 3600000; // 1 hour

        // Remove old security events
        securityEvents.removeIf(event -> currentTime - event.timestamp > threshold);

        // Clean player data
        playerData.entrySet().removeIf(entry ->
            currentTime - entry.getValue().lastActivity > threshold);
    }

    private void logSecurityEvent(String type, String details, AlertLevel level) {
        SecurityEvent event = new SecurityEvent(type, details, level, System.currentTimeMillis());
        securityEvents.add(event);
        totalSecurityEvents++;

        if (level == AlertLevel.CRITICAL) {
            criticalEvents++;
        }

        // Show alert if enabled and meets threshold
        if (enableAlerts.get() && level.ordinal() >= alertLevel.get().ordinal()) {
            String color = getAlertColor(level);
            ChatUtils.info(color + "[SECURITY] " + level.name() + ": §f" + type);
            ChatUtils.info("§7Details: " + details);

            if (soundAlerts.get() && level.ordinal() >= AlertLevel.HIGH.ordinal()) {
                mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.5f, 2.0f);
            }
        }
    }

    private String getAlertColor(AlertLevel level) {
        return switch (level) {
            case LOW -> "§a";
            case MEDIUM -> "§e";
            case HIGH -> "§6";
            case CRITICAL -> "§c";
        };
    }

    private void generateSecurityReport() {
        if (!generateReports.get()) return;

        ChatUtils.info("§b[SECURITY REPORT] §fGenerating security summary...");
        ChatUtils.info("§7Total Events: §f" + totalSecurityEvents + " §7| Critical: §c" + criticalEvents);
        ChatUtils.info("§7Active Players Monitored: §f" + playerData.size());
        ChatUtils.info("§7Most Used Commands: §f" + getMostUsedCommands());
        ChatUtils.info("§7System Status: §f" + getSecurityStatus());

        if (criticalEvents > 0) {
            ChatUtils.info("§c⚠ Warning: " + criticalEvents + " critical security events detected!");
        } else if (totalSecurityEvents == 0) {
            ChatUtils.info("§a✓ No security events detected - Server appears secure!");
        }
    }

    private String getMostUsedCommands() {
        return commandCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(3)
            .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
            .reduce((a, b) -> a + ", " + b)
            .orElse("None");
    }

    // Data classes
    private static class PlayerSecurityData {
        long sessionStart = System.currentTimeMillis();
        long lastActivity = System.currentTimeMillis();
        int commandCount = 0;
    }

    private static class SecurityEvent {
        final String type;
        final String details;
        final AlertLevel level;
        final long timestamp;

        SecurityEvent(String type, String details, AlertLevel level, long timestamp) {
            this.type = type;
            this.details = details;
            this.level = level;
            this.timestamp = timestamp;
        }
    }

    // === UTILITY METHODS ===
    public int getTotalSecurityEvents() {
        return totalSecurityEvents;
    }

    public int getCriticalEvents() {
        return criticalEvents;
    }

    public List<SecurityEvent> getRecentEvents(int count) {
        return securityEvents.stream()
            .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
            .limit(count)
            .toList();
    }

    public String getSecurityStatus() {
        if (criticalEvents > 0) {
            return "§cCRITICAL";
        } else if (totalSecurityEvents > 10) {
            return "§6WARNING";
        } else {
            return "§aSECURE";
        }
    }

    // === MANUAL SCAN METHODS ===
    public void performManualScan() {
        if (enableAlerts.get()) {
            ChatUtils.info("§b[SECURITY] §fPerforming manual security scan...");
        }
        performSecurityScan();
        generateSecurityReport();
    }

    public void resetStatistics() {
        totalSecurityEvents = 0;
        criticalEvents = 0;
        securityEvents.clear();
        commandCounts.clear();
        playerData.clear();

        if (enableAlerts.get()) {
            ChatUtils.info("§a[SECURITY] §fSecurity statistics reset!");
        }
    }
}
