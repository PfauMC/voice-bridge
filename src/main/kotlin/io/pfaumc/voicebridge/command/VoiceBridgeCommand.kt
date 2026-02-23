package io.pfaumc.voicebridge.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgePlugin
import io.pfaumc.voicebridge.session.ModType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

@Suppress("UnstableApiUsage")
object VoiceBridgeCommand {

    private val ACCENT = NamedTextColor.GOLD
    private val LABEL = NamedTextColor.GRAY
    private val VALUE = NamedTextColor.WHITE
    private val CMD = NamedTextColor.YELLOW
    private val SVC_COLOR = NamedTextColor.AQUA
    private val PV_COLOR = NamedTextColor.LIGHT_PURPLE
    private val DUAL_COLOR = NamedTextColor.GREEN

    fun buildCommand(plugin: VoiceBridgePlugin): LiteralCommandNode<CommandSourceStack> {
        return Commands.literal("voicebridge")
            .requires { it.sender.hasPermission("voicebridge.admin") }
            .executes { showHelp(it) }
            .then(
                Commands.literal("status")
                    .executes { showStatus(it, plugin) }
            )
            .then(
                Commands.literal("players")
                    .executes { showPlayers(it, plugin) }
            )
            .then(
                Commands.literal("reload")
                    .executes { reloadConfig(it, plugin) }
            )
            .then(
                Commands.literal("debug")
                    .executes { toggleDebug(it, plugin, null) }
                    .then(
                        Commands.argument("value", StringArgumentType.word())
                            .suggests { _, builder ->
                                builder.suggest("on").suggest("off").buildFuture()
                            }
                            .executes { ctx ->
                                toggleDebug(ctx, plugin, StringArgumentType.getString(ctx, "value"))
                            }
                    )
            )
            .build()
    }

    private fun header(text: String): Component =
        Component.text("══ ", ACCENT)
            .append(Component.text(text, ACCENT, TextDecoration.BOLD))
            .append(Component.text(" ══", ACCENT))

    private fun stat(label: String, value: Any): Component =
        Component.text("  $label: ", LABEL)
            .append(Component.text(value.toString(), VALUE))

    private fun cmdLine(cmd: String, description: String, vararg args: String): Component {
        val line = Component.text("  ", LABEL)
            .append(
                Component.text(cmd, CMD)
                    .clickEvent(ClickEvent.suggestCommand(cmd))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to paste", LABEL)))
            )
        if (args.isEmpty()) {
            return line.append(Component.text(" — $description", LABEL))
        }
        val argsComponent = args.fold(Component.empty()) { acc, arg ->
            acc.append(Component.text(" ", LABEL))
                .append(
                    Component.text(arg, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand("$cmd $arg"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to paste \"$cmd $arg\"", LABEL)))
                )
        }
        return line.append(argsComponent).append(Component.text(" — $description", LABEL))
    }

    private fun modTag(session: io.pfaumc.voicebridge.session.BridgeSession): Component {
        if (session.isDualMod()) {
            return Component.text("[SVC+PV]", DUAL_COLOR, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Dual-mod (both installed)", DUAL_COLOR)))
        }
        return when (session.modType) {
            ModType.SIMPLE_VOICE_CHAT -> Component.text("[SVC]", SVC_COLOR)
                .hoverEvent(HoverEvent.showText(Component.text("Simple Voice Chat", SVC_COLOR)))
            ModType.PLASMO_VOICE -> Component.text("[PV]", PV_COLOR)
                .hoverEvent(HoverEvent.showText(Component.text("Plasmo Voice", PV_COLOR)))
        }
    }

    private fun showStatus(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin): Int {
        val debug = plugin.bridgeConfig.debug
        ctx.source.sender.sendMessage(
            Component.join(
                JoinConfiguration.newlines(),
                header("Voice Bridge Status"),
                stat("Active sessions", BridgeMetrics.activeSessions.get()),
                stat("SVC → PV frames", BridgeMetrics.svcToPlasmoFrames.get()),
                stat("PV → SVC frames", BridgeMetrics.plasmoToSvcFrames.get()),
                stat("Dropped frames", BridgeMetrics.droppedFrames.get()),
                stat("Transcoded frames", BridgeMetrics.transcodingCount.get()),
                Component.text("  Debug: ", LABEL)
                    .append(
                        Component.text(
                            if (debug) "enabled" else "disabled",
                            if (debug) NamedTextColor.GREEN else NamedTextColor.RED
                        )
                    )
                    .hoverEvent(HoverEvent.showText(Component.text("Click to toggle", LABEL)))
                    .clickEvent(ClickEvent.runCommand("/voicebridge debug")),
                stat("Passthrough", plugin.bridgeConfig.passthrough),
            )
        )
        return Command.SINGLE_SUCCESS
    }

    private fun showPlayers(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin): Int {
        val sessions = plugin.sessionManager.getAllSessions()
        if (sessions.isEmpty()) {
            ctx.source.sender.sendMessage(Component.text("No active voice bridge sessions.", LABEL))
            return Command.SINGLE_SUCCESS
        }
        val lines = mutableListOf(header("Voice Bridge Players (${sessions.size})"))
        for (session in sessions) {
            val activeIndicator = if (session.active) {
                Component.text(" ●", NamedTextColor.GREEN)
                    .hoverEvent(HoverEvent.showText(Component.text("Active", NamedTextColor.GREEN)))
            } else {
                Component.text(" ○", NamedTextColor.DARK_GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text("Inactive", NamedTextColor.DARK_GRAY)))
            }
            lines += Component.text("  ", LABEL)
                .append(
                    Component.text(session.playerName, VALUE)
                        .hoverEvent(HoverEvent.showText(Component.text("UUID: ${session.playerUuid}", LABEL)))
                )
                .append(Component.text(" ", LABEL))
                .append(modTag(session))
                .append(activeIndicator)
        }
        ctx.source.sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines))
        return Command.SINGLE_SUCCESS
    }

    private fun reloadConfig(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin): Int {
        plugin.reloadBridgeConfig()
        ctx.source.sender.sendMessage(
            Component.text("✔ ", NamedTextColor.GREEN)
                .append(Component.text("Config reloaded.", VALUE))
        )
        return Command.SINGLE_SUCCESS
    }

    private fun toggleDebug(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin, value: String?): Int {
        val enabled = when (value?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> !plugin.bridgeConfig.debug
        }
        ctx.source.sender.sendMessage(
            Component.text(if (enabled) "✔ " else "✘ ", if (enabled) NamedTextColor.GREEN else NamedTextColor.RED)
                .append(Component.text("Debug logging ", VALUE))
                .append(
                    Component.text(
                        if (enabled) "enabled" else "disabled",
                        if (enabled) NamedTextColor.GREEN else NamedTextColor.RED,
                        TextDecoration.BOLD
                    )
                )
                .append(Component.text(" (runtime only)", LABEL))
        )
        return Command.SINGLE_SUCCESS
    }

    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sender.sendMessage(
            Component.join(
                JoinConfiguration.newlines(),
                header("Voice Bridge Commands"),
                cmdLine("/vb status", "Show bridge status and metrics"),
                cmdLine("/vb players", "List connected players and their voice mod"),
                cmdLine("/vb reload", "Reload configuration"),
                cmdLine("/vb debug", "Toggle debug logging", "on", "off"),
            )
        )
        return Command.SINGLE_SUCCESS
    }
}
