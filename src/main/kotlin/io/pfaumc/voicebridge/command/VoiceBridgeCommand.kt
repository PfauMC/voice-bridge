package io.pfaumc.voicebridge.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgePlugin

@Suppress("UnstableApiUsage")
object VoiceBridgeCommand {

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

    private fun showStatus(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin): Int {
        val sender = ctx.source.sender
        sender.sendMessage("=== Voice Bridge Status ===")
        sender.sendMessage("Active sessions: ${BridgeMetrics.activeSessions.get()}")
        sender.sendMessage("SVC -> PV frames: ${BridgeMetrics.svcToPlasmoFrames.get()}")
        sender.sendMessage("PV -> SVC frames: ${BridgeMetrics.plasmoToSvcFrames.get()}")
        sender.sendMessage("Dropped frames: ${BridgeMetrics.droppedFrames.get()}")
        sender.sendMessage("Transcoded frames: ${BridgeMetrics.transcodingCount.get()}")
        sender.sendMessage("Debug: ${plugin.bridgeConfig.debug}")
        sender.sendMessage("Passthrough: ${plugin.bridgeConfig.passthrough}")
        return Command.SINGLE_SUCCESS
    }

    private fun showPlayers(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin): Int {
        val sender = ctx.source.sender
        val sessions = plugin.sessionManager.getAllSessions()
        if (sessions.isEmpty()) {
            sender.sendMessage("No active voice bridge sessions.")
            return Command.SINGLE_SUCCESS
        }
        sender.sendMessage("=== Voice Bridge Players (${sessions.size}) ===")
        sessions.forEach { session ->
            sender.sendMessage(
                "  ${session.playerName}: ${session.modType.name} " +
                    "(active=${session.active})"
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun reloadConfig(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin): Int {
        plugin.reloadBridgeConfig()
        ctx.source.sender.sendMessage("Voice Bridge config reloaded.")
        return Command.SINGLE_SUCCESS
    }

    private fun toggleDebug(ctx: CommandContext<CommandSourceStack>, plugin: VoiceBridgePlugin, value: String?): Int {
        val enabled = when (value?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> !plugin.bridgeConfig.debug
        }
        ctx.source.sender.sendMessage("Debug logging ${if (enabled) "enabled" else "disabled"} (runtime only)")
        return Command.SINGLE_SUCCESS
    }

    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        sender.sendMessage("=== Voice Bridge Commands ===")
        sender.sendMessage("/voicebridge status - Show bridge status and metrics")
        sender.sendMessage("/voicebridge players - List connected players and their voice mod")
        sender.sendMessage("/voicebridge reload - Reload configuration")
        sender.sendMessage("/voicebridge debug [on|off] - Toggle debug logging")
        return Command.SINGLE_SUCCESS
    }
}
