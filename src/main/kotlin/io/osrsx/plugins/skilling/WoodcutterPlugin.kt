package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.RestockSpec
import io.osrsx.api.Skill
import io.osrsx.api.loadout
import io.osrsx.config.PluginConfig
import io.osrsx.config.isFalse
import io.osrsx.config.isTrue
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.PluginDescriptor
import io.osrsx.plugin.ScriptGui
import io.osrsx.script.Script
import io.osrsx.script.ScriptDslPlugin

/**
 * Woodcutting plugin, authored with the **Script DSL** ([ScriptDslPlugin]) over the shared
 * [skillGatherScript] — the loadout-era successor to the old `Gatherer`/`ToolManager` pair. This class only
 * names the tree, the action and the logs, and declares the axe as a **Loadout**; the provision → travel →
 * chop → bank/drop loop lives in the reusable script.
 *
 * At parity with the old woodcutter: provisions the best axe it owns through the Loadouts API (bought off the
 * GE only when you own none and "Buy axe if none" is on), web-walks to the nearest tree cluster when none is
 * in scene, auto-selects the best tree for your level, honours stop targets and shows an alt-drag stats
 * overlay ([SkillOverlay]).
 */
@PluginDescriptor(
    name = "Woodcutter",
    description = "Chops a configured tree and drops or banks the logs.",
    author = "osrsx",
    tags = ["skilling", "woodcutting", "gathering"],
)
class WoodcutterPlugin : ScriptDslPlugin(), HasOverlay {

    object Config : PluginConfig("woodcutter") {
        var auto by boolItem("auto", "Auto-select tree", false,
            "Pick the best tree for your level and navigate to it (ignores Tree/Log below)", section = "Setup")
        // Manual tree/log pickers: confined to real trees / real logs (from the shared tier table), hidden
        // while "Auto-select" is on, and kept in lockstep BOTH ways by the link below — pick the Willow tree
        // and the log picker follows to Willow logs, and vice versa.
        var tree by objectItem("tree", "Tree name", "Tree", "Object to chop",
            choices = SkillTiers.TREES.map { it.name }, browse = true, distinct = true, visibleIf = isFalse("auto"))
        var logs by itemItem("logs", "Log name", "Logs", "Item name of the logs produced",
            choices = SkillTiers.TREES.map { it.log }, browse = true, visibleIf = isFalse("auto"))

        init { link("tree", "logs", SkillTiers.TREES.associate { it.name to it.log }) }
        var bank by boolItem("bank", "Bank logs", false, "Bank the logs when full (else drop them)")
        var getBestAxe by boolItem("getBestAxe", "Get best axe", true,
            "Before chopping, provision the best axe your level allows through the bank (see 'Buy axe if none')",
            section = "Setup")
        var buyMissingFromGE by boolItem("buyMissingFromGE", "Buy axe if none", false,
            "When you own no usable axe at all, buy the best one your level allows off the Grand Exchange",
            section = "Setup", visibleIf = isTrue("getBestAxe"))
        var walk by boolItem("walk", "Walk to trees", true,
            "When no tree is nearby, web-walk to the nearest catalogued cluster", section = "Setup")
        // The return tile only matters once you're banking; the power-drop delays only when you're dropping.
        var home by stringItem("home", "Tree tile", "", "Optional 'x,y[,plane]' to walk back to after banking",
            visibleIf = isTrue("bank"))

        var minDrop by intItem("minDrop", "Min drop (ms)", 90, 20, 2000, "Fastest per-log pause when power-dropping", "Logs", visibleIf = isFalse("bank"))
        var maxDrop by intItem("maxDrop", "Max drop (ms)", 230, 20, 3000, "Slowest per-log pause when power-dropping", "Logs", visibleIf = isFalse("bank"))

        var lockInput by boolItem("lockInput", "Lock user input", false,
            "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")

        var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99, "Stop when Woodcutting hits this level (0 = never)", "Stopping")
        var stopAtLogs by intItem("stopAtLogs", "Stop at logs", 0, 0, 1_000_000, "Stop after this many logs (0 = never)", "Stopping")
        var stopAtGp by intItem("stopAtGp", "Stop at GP", 0, 0, 2_000_000_000, "Stop once the logs are worth this many GP (0 = never)", "Stopping")
        var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000, "Stop after this many minutes (0 = never)", "Stopping")
    }

    override fun config() = Config

    private val stats by lazy { SkillStats(ctx, Skill.WOODCUTTING) }
    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, count = { Config.stopAtLogs },
            gp = { Config.stopAtGp }, minutes = { Config.stopAfterMins },
            gpEach = { prices.price(logName()) })
    }

    private fun activeTree(): TreeTier = SkillTiers.bestForLevel(SkillTiers.TREES, skills.real(Skill.WOODCUTTING), "Auto")
    private fun treeName(): String = if (Config.auto) activeTree().name else Config.tree
    private fun logName(): String = if (Config.auto) activeTree().log else Config.logs

    /** The best axe to provision: the best one OWNED (bank/inventory/equipment), or — only when "Buy axe if
     *  none" is on — the best one the GE could sell. Null when axe-fetch is off. */
    private fun bestAxe(): String? {
        if (!Config.getBestAxe) return null
        val owned = toolbelt.best(Skill.WOODCUTTING, false)
        val choice = owned ?: if (Config.buyMissingFromGE) toolbelt.best(Skill.WOODCUTTING, true) else null
        return choice?.name
    }

    override fun onScriptStart() {
        stats.start()
        stats.carried = { inventory.count(logName()) }
        // Heal any saved tree<->logs mismatch on start by re-applying the declarative link.
        Config.applyLinks("tree")
    }

    override fun onScriptStop() { if (input.isLocked()) input.unlock() }

    override fun script(): Script = skillGatherScript(
        GatherSpec(
            name = "woodcutter",
            loadout = {
                loadout("woodcutter") {
                    val axe = bestAxe()
                    if (axe != null) {
                        // Wield the best axe (frees an inventory slot); the run proceeds with it in the
                        // inventory if the Attack level is too low to wield. Buy one only when the bank is dry.
                        equip(ItemRef(axe), restock = if (Config.buyMissingFromGE) RestockSpec(ItemRef(axe), 1) else null)
                    }
                }
            },
            required = { bestAxe()?.let { listOf(ToolNeed(it)) } ?: emptyList() },
            findResource = { objects.closest(treeName(), "Chop down") },
            action = { "Chop down" },
            products = { listOf(ItemRef(logName())) },
            bank = { Config.bank },
            resourceName = { treeName().takeIf { Config.walk && it.isNotBlank() } },
            resourceKind = ResourceKind.OBJECT,
            homeTile = { configuredTile(Config.home) },
            buyMissingFromGE = { Config.buyMissingFromGE },
            dropParams = { DropParams(Config.minDrop, Config.maxDrop, 5, 400, 900) },
            lockInput = { Config.lockInput },
            stopReason = { stops.reason() },
            stats = stats,
        )
    )

    override fun overlayTitle() = "Woodcutting"

    override fun onOverlay(gui: ScriptGui) {
        val worth = stats.output() * prices.price(logName())
        SkillOverlay.render(gui, stats, listOf(
            "Target" to treeName(),
            (if (Config.bank) "Logs banked" else "Logs dropped")
                to "${SkillOverlay.commas(stats.output())} (${SkillOverlay.compact(stats.perHour(worth))} gp/hr)",
        ))
    }
}
